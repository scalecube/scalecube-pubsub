package io.scalecube.pubsub.example;

import io.aeron.Publication;
import org.agrona.MutableDirectBuffer;
import org.slf4j.Logger;
import static java.nio.charset.StandardCharsets.UTF_8;

public final class Utilities {
  private Utilities() {

  }

  static void send(final Publication pub, final MutableDirectBuffer buffer, final String message) {
    final byte[] value = message.getBytes(UTF_8);
    buffer.putBytes(0, value);
    final long result = pub.offer(buffer, 0, value.length);
    
    if (result < 0L) {
      System.out.println("could not send: " + Long.valueOf(result));
    }
  }
}
