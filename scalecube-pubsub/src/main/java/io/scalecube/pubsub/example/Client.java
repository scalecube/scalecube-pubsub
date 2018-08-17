package io.scalecube.pubsub.example;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;
import io.aeron.Aeron;
import io.aeron.ChannelUriStringBuilder;
import io.aeron.ExclusivePublication;
import io.aeron.FragmentAssembler;
import io.aeron.Image;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import org.agrona.BufferUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalDateTime;

final class Client extends Thread {
  private final Logger log;
  private final MediaDriver.Context media_context;
  private final MediaDriver media;
  private final Aeron.Context aeron_context;
  private final Aeron aeron;
  private final UnsafeBuffer buffer;
  private final FragmentAssembler fragment_assembler;
  private final String server_address;
  private final int server_control_port;
  private final int server_data_port;
  private final String local_address;
  private final int local_port;

  Client(final String server_address, final int server_control_port, final int server_data_port,
      final String local_address, final int local_port) {
    super("client");

    this.local_address = local_address;
    this.local_port = local_port;
    this.server_address = server_address;
    this.server_control_port = server_control_port;
    this.server_data_port = server_data_port;

    this.setDaemon(false);
    this.log = LoggerFactory.getLogger("Client [" + local_port + "]");

    this.media_context =
        new MediaDriver.Context().dirDeleteOnStart(true).aeronDirectoryName("/dev/shm/aeron-client-" + local_port);
    this.media = MediaDriver.launch(this.media_context);

    this.aeron_context = new Aeron.Context().aeronDirectoryName("/dev/shm/aeron-client-" + local_port);
    this.aeron = Aeron.connect(this.aeron_context);

    this.buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(2048, 16));
    this.fragment_assembler = new FragmentAssembler(new Parser(this.log));
  }

  private static String clientMessage() {
    return new StringBuilder(128).append("Client HELLO: ").append(LocalDateTime.now().format(ISO_LOCAL_DATE_TIME))
        .toString();
  }

  public static void main(final String[] args) {
    final Client c = new Client("localhost", 9090, 9091, "localhost", 8080);
    c.start();
  }

  @Override
  public void run() {
    this.log.debug("start");

    /*
     * Create a subscription to read data from the server. This uses dynamic MDC to will send messages
     * to the server's control port, and the server will react by data to the local address and port
     * combination specified here.
     */

    final String sub_uri = new ChannelUriStringBuilder().mtu(Shared.MTU).reliable(Boolean.TRUE).media("udp")
        .endpoint(this.local_address + ":" + this.local_port)
        .controlEndpoint(this.server_address + ":" + this.server_control_port).controlMode("dynamic").build();

    this.log.debug("opening data subscription: {}", sub_uri);

    final Subscription sub =
        this.aeron.addSubscription(sub_uri, Shared.STREAM_ID, this::onImageAvailable, this::onImageUnavailable);

    /*
     * Create a publication for sending data to the server.
     */

    final String pub_uri = new ChannelUriStringBuilder().mtu(Shared.MTU).reliable(Boolean.TRUE).media("udp")
        .endpoint(this.server_address + ":" + this.server_data_port).build();

    this.log.debug("opening data publication: {}", pub_uri);

    final ExclusivePublication pub = this.aeron.addExclusivePublication(pub_uri, Shared.STREAM_ID);

    while (true) {
      this.log.trace("sub connected: {}", Boolean.valueOf(sub.isConnected()));
      if (sub.isConnected()) {
        sub.poll(this.fragment_assembler, 10);
      }

      this.log.trace("pub connected: {}", Boolean.valueOf(pub.isConnected()));
      if (pub.isConnected()) {
        Utilities.send(this.log, pub, this.buffer, clientMessage());
      }

      try {
        Thread.sleep(2000L);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void onImageUnavailable(final Image image) {
    this.log.debug("onImageUnavailable: [0x{}] {}", String.format("%08x", Integer.valueOf(image.sessionId())),
        image.sourceIdentity());
  }

  private void onImageAvailable(final Image image) {
    this.log.debug("onImageAvailable: [0x{}] {}", String.format("%08x", Integer.valueOf(image.sessionId())),
        image.sourceIdentity());
  }
}