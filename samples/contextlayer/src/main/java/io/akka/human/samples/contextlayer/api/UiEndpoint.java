package io.akka.human.samples.contextlayer.api;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpResponses;

/**
 * The Decisions, Links and Cases screens, built into {@code static-resources/}. A path that names a
 * file serves it; any other path serves {@code index.html}, so the single-page application routes it.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint
public class UiEndpoint extends AbstractHttpEndpoint {

  @Get("/")
  public HttpResponse index() {
    return HttpResponses.staticResource("index.html");
  }

  @Get("/**")
  public HttpResponse screens(HttpRequest request) {
    var path = request.getUri().path();
    var last = path.substring(path.lastIndexOf('/') + 1);
    return last.contains(".") ? HttpResponses.staticResource(request, "/") : HttpResponses.staticResource("index.html");
  }
}
