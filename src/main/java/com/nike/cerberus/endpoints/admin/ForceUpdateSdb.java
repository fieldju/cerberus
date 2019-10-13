package com.nike.cerberus.endpoints.admin;

import com.nike.cerberus.domain.SDBMetadata;
import com.nike.cerberus.domain.SafeDepositBoxV2;
import com.nike.cerberus.endpoints.AdminStandardEndpoint;
import com.nike.riposte.server.http.RequestInfo;
import com.nike.riposte.server.http.ResponseInfo;
import com.nike.riposte.util.Matcher;
import io.netty.channel.ChannelHandlerContext;

import javax.ws.rs.core.SecurityContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class ForceUpdateSdb extends AdminStandardEndpoint<SafeDepositBoxV2, SafeDepositBoxV2> {

  @Override
  public CompletableFuture<ResponseInfo<SafeDepositBoxV2>> doExecute(RequestInfo<SafeDepositBoxV2> request, Executor longRunningTaskExecutor, ChannelHandlerContext ctx, SecurityContext securityContext) {
    return null;
  }

  @Override
  public Matcher requestMatcher() {
    return null;
  }
}
