/*
 * Copyright (c) 2017 Nike, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nike.cerberus.service;

import com.nike.backstopper.exception.ApiException;
import com.nike.cerberus.domain.IamRolePermission;
import com.nike.cerberus.domain.Role;
import com.nike.cerberus.domain.SDBMetadata;
import com.nike.cerberus.domain.SDBMetadataResult;
import com.nike.cerberus.domain.SafeDepositBox;
import com.nike.cerberus.domain.UserGroupPermission;
import com.nike.cerberus.error.InvalidCategoryNameApiError;
import com.nike.cerberus.error.InvalidIamRoleArnApiError;
import com.nike.cerberus.error.InvalidRoleNameApiError;
import com.nike.cerberus.util.UuidSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A service that can perform admin tasks around SDB metadata
 */
public class MetadataService {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final SafeDepositBoxService safeDepositBoxService;
    private final CategoryService categoryService;
    private final RoleService roleService;
    private final UuidSupplier uuidSupplier;

    @Inject
    public MetadataService(SafeDepositBoxService safeDepositBoxService,
                           CategoryService categoryService,
                           RoleService roleService,
                           UuidSupplier uuidSupplier) {

        this.safeDepositBoxService = safeDepositBoxService;
        this.categoryService = categoryService;
        this.roleService = roleService;
        this.uuidSupplier = uuidSupplier;
    }

    /**
     * Creates or Updates an SDB using saved off metadata.
     * This method differs from SafeDepositBoxService::createSafeDepositBox and SafeDepositBoxService::updateSafeDepositBox
     * only in that this method sets the created by and last updated fields which are normally sourced automatically.
     *
     * This is an admin function so that backed up SDB metadata can easily be restored.
     * An example would be a cross region recovery event where you are restoring backed up data from a different
     * region / cerberus environment
     *
     * @param sdbMetadata SDB Payload to restore
     */
    public void restoreMetadata(SDBMetadata sdbMetadata, String adminUser) {
        logger.info("Restoring metadata for SDB: {}", sdbMetadata.getName());

        Optional<String> sdbId = safeDepositBoxService.getSafeDepositBoxIdByName(sdbMetadata.getName());
        String id;
        if (sdbId.isPresent()) {
            id = sdbId.get();

            logger.info("Found existing SDB for {} with id {}, forcing restore", sdbMetadata.getName(), id);
        } else {
            // create
            id = uuidSupplier.get();
            logger.info("No SDB found for {}, creating new SDB", sdbMetadata.getName());
        }

        // Map the string category name to a category id
        Optional<String> categoryOpt = categoryService.getCategoryIdByName(sdbMetadata.getCategory());
        if (! categoryOpt.isPresent()) {
            throw ApiException.newBuilder()
                    .withApiErrors(new InvalidCategoryNameApiError(sdbMetadata.getCategory()))
                    .build();
        }
        String categoryId = categoryOpt.get();

        Set<UserGroupPermission> userGroupPermissionSet = new HashSet<>();
        sdbMetadata.getUserGroupPermissions().forEach((groupName, roleName) -> {
            userGroupPermissionSet.add(new UserGroupPermission()
                    .withName(groupName)
                    .withRoleId(getRoleIdFromName(roleName))
            );
        });

        Set<IamRolePermission> iamRolePermissionSet = new HashSet<>();
        sdbMetadata.getIamRolePermissions().forEach((iamRoleArn, roleName) -> {
            Pattern iamRoleArnParserPattern = Pattern.compile("arn:aws:iam::(?<accountId>.*?):role/(?<roleName>.*)");
            Matcher iamRoleArnParserMatcher = iamRoleArnParserPattern.matcher(iamRoleArn);
            if (! iamRoleArnParserMatcher.find()) {
                throw ApiException.newBuilder()
                        .withApiErrors(new InvalidIamRoleArnApiError(sdbMetadata.getCategory()))
                        .build();
            }

            iamRolePermissionSet.add(new IamRolePermission()
                    .withAccountId(iamRoleArnParserMatcher.group("accountId"))
                    .withIamRoleName(iamRoleArnParserMatcher.group("roleName"))
                    .withRoleId(getRoleIdFromName(roleName))
            );
        });


        SafeDepositBox sdb = new SafeDepositBox();
        sdb.setId(id);
        sdb.setPath(sdbMetadata.getPath());
        sdb.setCategoryId(categoryId);
        sdb.setName(sdbMetadata.getName());
        sdb.setOwner(sdbMetadata.getOwner());
        sdb.setDescription(sdbMetadata.getDescription());
        sdb.setCreatedTs(sdbMetadata.getCreatedTs());
        sdb.setLastUpdatedTs(sdbMetadata.getLastUpdatedTs());
        sdb.setCreatedBy(sdbMetadata.getCreatedBy());
        sdb.setLastUpdatedBy(sdbMetadata.getLastUpdatedBy());
        sdb.setUserGroupPermissions(userGroupPermissionSet);
        sdb.setIamRolePermissions(iamRolePermissionSet);

        safeDepositBoxService.restoreSafeDepositBox(sdb, adminUser);
    }

    private String getRoleIdFromName(String roleName) {
        // map the string role name to a role id
        Optional<Role> role = roleService.getRoleByName(roleName);
        if (! role.isPresent()) {
            throw ApiException.newBuilder()
                    .withApiErrors(new InvalidRoleNameApiError(roleName))
                    .build();
        }
        return role.get().getId();
    }

    /**
     * Method for retrieving metadata about SDBs sorted by created date.
     *
     * @param limit  The int limit for paginating.
     * @param offset The int offset for paginating.
     * @return SDBMetadataResult of meta data.
     */
    public SDBMetadataResult getSDBMetadata(int limit, int offset) {
        SDBMetadataResult result = new SDBMetadataResult();
        result.setLimit(limit);
        result.setOffset(offset);
        result.setTotalSDBCount(safeDepositBoxService.getTotalNumberOfSafeDepositBoxes());
        result.setHasNext(result.getTotalSDBCount() > (offset + limit));
        if (result.isHasNext()) {
            result.setNextOffset(offset + limit);
        }
        List<SDBMetadata> sdbMetadataList = getSDBMetadataList(limit, offset);
        result.setSafeDepositBoxMetadata(sdbMetadataList);
        result.setSdbCountInResult(sdbMetadataList.size());

        return result;
    }

    protected List<SDBMetadata> getSDBMetadataList(int limit, int offset) {
        List<SDBMetadata> sdbs = new LinkedList<>();

        // Collect the categories.
        Map<String, String> catIdToStringMap = categoryService.getCategoryIdToCategoryNameMap();
        // Collect the roles
        Map<String, String> roleIdToStringMap = roleService.getRoleIdToStringMap();
        // Collect The SDB Records
        List<SafeDepositBox> safeDepositBoxes = safeDepositBoxService.getSafeDepositBoxes(limit, offset);

        // for each SDB collect the user and iam permissions and add to result
        safeDepositBoxes.forEach(sdb -> {
            SDBMetadata data = new SDBMetadata();
            data.setName(sdb.getName());
            data.setPath(sdb.getPath());
            data.setDescription(sdb.getDescription());
            data.setCategory(catIdToStringMap.get(sdb.getCategoryId()));
            data.setCreatedBy(sdb.getCreatedBy());
            data.setCreatedTs(sdb.getCreatedTs());
            data.setLastUpdatedBy(sdb.getLastUpdatedBy());
            data.setLastUpdatedTs(sdb.getLastUpdatedTs());
            data.setOwner(sdb.getOwner());
            data.setUserGroupPermissions(getUserGroupPermissionsMap(roleIdToStringMap, sdb.getUserGroupPermissions()));
            data.setIamRolePermissions(getIamRolePermissionMap(roleIdToStringMap, sdb.getIamRolePermissions()));
            sdbs.add(data);
        });

        return sdbs;
    }

    protected Map<String, String> getUserGroupPermissionsMap(Map<String, String> roleIdToStringMap,
                                                             Set<UserGroupPermission> permissions) {

        Map<String, String> permissionsMap = new HashMap<>();
        permissions.forEach(permission ->
                permissionsMap.put(permission.getName(), roleIdToStringMap.get(permission.getRoleId())));

        return permissionsMap;
    }

    protected Map<String,String> getIamRolePermissionMap(Map<String, String> roleIdToStringMap,
                                                         Set<IamRolePermission> iamPerms) {

        Map<String, String> iamRoleMap = new HashMap<>(iamPerms.size());
        iamPerms.forEach(perm -> {
            String role = roleIdToStringMap.get(perm.getRoleId());

            iamRoleMap.put(String.format(AuthenticationService.AWS_IAM_ROLE_ARN_TEMPLATE,
                    perm.getAccountId(), perm.getIamRoleName()), role);
        });
        return iamRoleMap;
    }
}