package io.akka.human.api.spi;

import io.akka.human.api.Reviewer;
import java.util.Map;
import java.util.Optional;

/**
 * Who may read and act over {@code /oversight}, for services with their own authorization. The
 * library calls it before every route with the reviewer the request carries and the request's
 * headers; an answer refuses the call with 403 and that reason.
 */
public interface AccessPolicy {

  Optional<String> refuse(String route, Reviewer reviewer, Map<String, String> headers);

  AccessPolicy ALLOW_ALL = (route, reviewer, headers) -> Optional.empty();
}
