package io.scalecube.pubsub.example;

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
import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;

final class Server extends Thread {
  private final Logger log;
  private final MediaDriver.Context media_context;
  private final MediaDriver media;
  private final Aeron.Context aeron_context;
  private final Aeron aeron;
  private final UnsafeBuffer buffer;
  private final FragmentAssembler fragment_assembler;
  private final String local_address;
  private final int local_control_port;
  private final int local_data_port;

  Server(final String address, final int control_port, final int data_port) {
    super("server");

    this.log = LoggerFactory.getLogger("Server");

    this.local_address = address;
    this.local_control_port = control_port;
    this.local_data_port = data_port;

    this.media_context = new MediaDriver.Context().dirDeleteOnStart(true).aeronDirectoryName("/dev/shm/aeron-server");
    this.media = MediaDriver.launch(this.media_context);

    this.aeron_context = new Aeron.Context().aeronDirectoryName("/dev/shm/aeron-server");
    this.aeron = Aeron.connect(this.aeron_context);

    this.buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(2048, 16));
    this.fragment_assembler = new FragmentAssembler(new Parser(this.log));
  }

  private static String serverMessage() {
    return new StringBuilder(128).append("Server HELLO: ").append(LocalDateTime.now().format(ISO_LOCAL_DATE_TIME))
        .toString();
  }

  public static void main(final String[] args) {

    final Server s = new Server("localhost",9090, 9091);
    s.start();
  }

  @Override
  public void run() {
    this.log.debug("start");

    final String pub_uri = new ChannelUriStringBuilder().mtu(Shared.MTU).reliable(Boolean.TRUE).media("udp")
        .controlEndpoint(this.local_address + ":" + this.local_control_port).build();

    this.log.debug("opening control publication: {}", pub_uri);

    final ExclusivePublication pub = this.aeron.addExclusivePublication(pub_uri, Shared.STREAM_ID);

    final String sub_uri = new ChannelUriStringBuilder().mtu(Shared.MTU).reliable(Boolean.TRUE).media("udp")
        .endpoint(this.local_address + ":" + this.local_data_port).build();

    this.log.debug("opening data subscription: {}", sub_uri);

    final Subscription sub =
        this.aeron.addSubscription(sub_uri, Shared.STREAM_ID, this::onImageAvailable, this::onImageUnavailable);

    while (true) {
      this.log.trace("pub connected: {}", Boolean.valueOf(pub.isConnected()));
      if (pub.isConnected()) {
        Utilities.send(this.log, pub, this.buffer, serverMessage());
      }

      this.log.trace("sub connected: {}", Boolean.valueOf(sub.isConnected()));
      if (sub.isConnected()) {
        sub.poll(this.fragment_assembler, 10);
      }

      try {
        Thread.sleep(1000L);
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