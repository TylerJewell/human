package io.akka.human.engine;

import static io.akka.human.engine.Fx.control;
import static io.akka.human.engine.Fx.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.human.api.Relations;
import org.junit.jupiter.api.Test;

/** The names {@code related[].relation} can carry: the known ones, and a service's own under {@code x-}. */
class RelationsTest {

  @Test
  void knownAndCustomNamesAreValid() {
    for (var name : Relations.KNOWN) assertTrue(Relations.valid(name), name);
    assertTrue(Relations.valid("x-supersedes"));
    assertTrue(Relations.valid("x-halt-2"));
  }

  @Test
  void otherNamesAreNot() {
    for (var name : new String[] {"follows", "x-", "X-follows", "x-Follows", "x--a", "", " "}) {
      assertFalse(Relations.valid(name), name);
    }
    assertFalse(Relations.valid(null));
  }

  @Test
  void aRequestRefusesAnUnknownRelation() {
    var settings = control("pt-00001");
    var ok = request(settings).related("he-0", Relations.RERAISED_AFTER).related("he-1", "x-follows").build();
    assertEquals(2, ok.related().size());
    assertThrows(IllegalArgumentException.class, () -> request(settings).related("he-0", "follows").build());
  }
}
