package com.datastax.simulacron.server;

import com.datastax.oss.protocol.internal.Compressor;
import com.datastax.oss.protocol.internal.Frame;
import com.datastax.oss.protocol.internal.FrameCodec;
import com.datastax.simulacron.common.cluster.Cluster;
import com.datastax.simulacron.common.cluster.DataCenter;
import com.datastax.simulacron.common.cluster.Node;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * The main point of entry for registering and binding Clusters to the network. Provides methods for
 * registering and unregistering Clusters. When a Cluster is registered, all applicable nodes are
 * bound to their respective network interfaces and are ready to handle native protocol traffic.
 */
public final class Server {

  private static final Logger logger = LoggerFactory.getLogger(Server.class);

  private static final AttributeKey<BoundNode> HANDLER = AttributeKey.valueOf("NODE");

  private static final FrameCodec<ByteBuf> frameCodec =
      FrameCodec.defaultServer(new ByteBufCodec(), Compressor.none());

  /** The bootstrap responsible for binding new listening server channels. */
  private final ServerBootstrap serverBootstrap;

  /**
   * A resolver for deriving the next {@link SocketAddress} to assign a {@link Node} if it does not
   * provide one.
   */
  private final AddressResolver addressResolver;

  /**
   * The amount of time in nanoseconds to allow for waiting for network interfaces to bind for
   * nodes.
   */
  private final long bindTimeoutInNanos;

  /** Mapping of registered {@link Cluster} instances to their identifier. */
  private final Map<UUID, Cluster> clusters = new ConcurrentHashMap<>();

  private Server(
      AddressResolver addressResolver, ServerBootstrap serverBootstrap, long bindTimeoutInNanos) {
    this.serverBootstrap = serverBootstrap;
    this.addressResolver = addressResolver;
    this.bindTimeoutInNanos = bindTimeoutInNanos;
  }

  /**
   * Registers a {@link Cluster} and binds it's {@link Node} instances to their respective
   * interfaces. If any members of the cluster lack an id, this will assign a random one for them.
   * If any nodes lack an address, this will assign one based on the configured {@link
   * AddressResolver}.
   *
   * <p>The given future will fail if not completed after the configured {@link
   * Server.Builder#withBindTimeout(long, TimeUnit)}.
   *
   * @param cluster cluster to register
   * @return A future that when it completes provides an updated {@link Cluster} with ids and
   *     addresses assigned. Note that the return value is not the same object as the input.
   */
  public CompletableFuture<Cluster> register(Cluster cluster) {
    UUID clusterId = UUID.randomUUID();
    Cluster c = Cluster.builder().copy(cluster).withId(clusterId).build();

    List<CompletableFuture<Node>> bindFutures = new ArrayList<>();

    for (DataCenter dataCenter : cluster.getDataCenters()) {
      UUID dcId = UUID.randomUUID();
      DataCenter dc = DataCenter.builder(c).copy(dataCenter).withId(dcId).build();

      for (Node node : dataCenter.getNodes()) {
        bindFutures.add(bindInternal(node, dc));
      }
    }

    clusters.put(clusterId, c);

    List<BoundNode> nodes = new ArrayList<>(bindFutures.size());
    Throwable exception = null;
    boolean timedOut = false;
    // Evaluate each future, if any fail capture the exception and record all successfully
    // bound nodes.
    long start = System.nanoTime();
    for (CompletableFuture<Node> f : bindFutures) {
      try {
        if (timedOut) {
          Node node = f.getNow(null);
          if (node != null) {
            nodes.add((BoundNode) node);
          }
        } else {
          nodes.add((BoundNode) f.get(bindTimeoutInNanos, TimeUnit.NANOSECONDS));
        }
        if (System.nanoTime() - start > bindTimeoutInNanos) {
          timedOut = true;
        }
      } catch (TimeoutException te) {
        timedOut = true;
        exception = te;
      } catch (Exception e) {
        exception = e.getCause();
      }
    }

    // If there was a failure close all bound nodes.
    if (exception != null) {
      final Throwable e = exception;
      // Close each node
      List<CompletableFuture<Node>> futures =
          nodes.stream().map(this::close).collect(Collectors.toList());

      CompletableFuture<Cluster> future = new CompletableFuture<>();
      // On completion of unbinding all nodes, fail the returned future.
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[] {}))
          .handle(
              (v, ex) -> {
                future.completeExceptionally(e);
                return v;
              });
      return future;
    } else {
      return CompletableFuture.allOf(bindFutures.toArray(new CompletableFuture[] {}))
          .thenApply(__ -> c);
    }
  }

  /**
   * Unregisters a {@link Cluster} and closes all listening network interfaces associated with it.
   *
   * <p>If the cluster is not currently registered the returned future will fail with an {@link
   * IllegalArgumentException}.
   *
   * @param clusterId id of the cluster.
   * @return A future that when completed provides the unregistered cluster as it existed in the
   *     registry, may not be the same object as the input.
   */
  public CompletableFuture<Cluster> unregister(UUID clusterId) {
    CompletableFuture<Cluster> future = new CompletableFuture<>();
    if (clusterId == null) {
      future.completeExceptionally(new IllegalArgumentException("Null id provided"));
    } else {
      Cluster foundCluster = clusters.get(clusterId);
      List<CompletableFuture<Node>> closeFutures = new ArrayList<>();
      if (foundCluster != null) {
        // Close socket on each node.
        for (DataCenter dataCenter : foundCluster.getDataCenters()) {
          for (Node node : dataCenter.getNodes()) {
            BoundNode boundNode = (BoundNode) node;
            closeFutures.add(close(boundNode));
          }
        }
        CompletableFuture.allOf(closeFutures.toArray(new CompletableFuture[] {}))
            .thenAccept(
                __ -> {
                  // remove cluster and complete future.
                  clusters.remove(clusterId);
                  future.complete(foundCluster);
                });
      } else {
        future.completeExceptionally(new IllegalArgumentException("Cluster not found."));
      }
    }

    return future;
  }

  /**
   * Convenience method that registers a {@link Node} by wrapping it in a 'dummy' {@link Cluster}
   * and registering that.
   *
   * <p>Note that if the given {@link Node} belongs to a {@link Cluster}, the returned future will
   * fail with an {@link IllegalArgumentException}.
   *
   * @param node node to register.
   * @return A future that when it completes provides an updated {@link Cluster} with ids and
   *     addresses assigned. Note that the return value is not the same object as the input.
   */
  public CompletableFuture<Node> register(Node node) {
    if (node.getDataCenter() != null) {
      CompletableFuture<Node> future = new CompletableFuture<>();
      future.completeExceptionally(
          new IllegalArgumentException("Node belongs to a Cluster, should be standalone."));
      return future;
    }
    // Wrap node in dummy cluster
    UUID clusterId = UUID.randomUUID();
    Cluster dummyCluster = Cluster.builder().withId(clusterId).withName("dummy").build();
    UUID dcId = UUID.randomUUID();
    DataCenter dummyDataCenter =
        dummyCluster.addDataCenter().withName("dummy").withId(dcId).build();

    clusters.put(clusterId, dummyCluster);

    return bindInternal(node, dummyDataCenter);
  }

  /**
   * Unregisters a {@link Node} and closes all listening network interfaces associated with it.
   *
   * <p>If the node cannot be found, the future will fail with an {@link IllegalArgumentException}.
   *
   * <p>Also note that if the node belongs to a Cluster with more than 1 node, the future will fail
   * with an {@link IllegalArgumentException}.
   *
   * @param nodeId id of node to register.
   * @return A future that when completed provides the unregistered node as it existed in the
   *     registry, may not be the same object as the input.
   */
  public CompletableFuture<Node> unregisterNode(UUID nodeId) {
    // Find the Node among all registered single-node clusters.
    Optional<Node> nodeOption =
        clusters
            .values()
            .stream()
            .flatMap(c -> c.getNodes().stream())
            .filter(n -> n.getId().equals(nodeId))
            .findFirst();
    if (nodeOption.isPresent()) {
      Node node = nodeOption.get();
      Cluster cluster = node.getCluster();
      if (cluster.getNodes().size() > 1) {
        CompletableFuture<Node> future = new CompletableFuture<>();
        future.completeExceptionally(
            new IllegalArgumentException(
                "Node belongs to a Cluster with "
                    + cluster.getNodes().size()
                    + ", cannot be unregistered alone"));
        return future;
      }
      return unregister(cluster.getId()).thenApply(__ -> node);
    } else {
      CompletableFuture<Node> future = new CompletableFuture<>();
      future.completeExceptionally(
          new IllegalArgumentException("No Node found with id: " + nodeId));
      return future;
    }
  }

  /** @return a map of registered {@link Cluster} instances keyed by their identifier. */
  public Map<UUID, Cluster> getClusterRegistry() {
    return this.clusters;
  }

  private CompletableFuture<Node> bindInternal(Node refNode, DataCenter parent) {
    UUID nodeId = UUID.randomUUID();
    // Use node's address if set, otherwise generate a new one.
    SocketAddress address =
        refNode.getAddress() != null ? refNode.getAddress() : addressResolver.get();
    CompletableFuture<Node> f = new CompletableFuture<>();
    ChannelFuture bindFuture = this.serverBootstrap.bind(address);
    bindFuture.addListener(
        (ChannelFutureListener)
            channelFuture -> {
              if (channelFuture.isSuccess()) {
                BoundNode node =
                    new BoundNode(
                        address,
                        refNode.getName(),
                        nodeId,
                        refNode.getCassandraVersion(),
                        refNode.getPeerInfo(),
                        parent,
                        channelFuture.channel());
                logger.info("Bound {} to {}", node, channelFuture.channel());
                channelFuture.channel().attr(HANDLER).set(node);
                f.complete(node);
              } else {
                // If failed, propagate it.
                f.completeExceptionally(channelFuture.cause());
              }
            });

    return f;
  }

  private CompletableFuture<Node> close(BoundNode node) {
    CompletableFuture<Node> future = new CompletableFuture<>();
    node.channel
        .close()
        .addListener(
            channelFuture -> {
              future.complete(node);
            });
    return future;
  }

  /**
   * Convenience method that provides a {@link Builder} that uses NIO for networking traffic, which
   * should be the typical case.
   *
   * @return Builder that is set up with {@link NioEventLoopGroup} and {@link
   *     NioServerSocketChannel}.
   */
  public static Builder builder() {
    return builder(new NioEventLoopGroup(), NioServerSocketChannel.class);
  }

  /**
   * Constructs a {@link Builder} that uses the given {@link EventLoopGroup} and {@link
   * ServerChannel} to construct a {@link ServerBootstrap} to be used by this {@link Server}.
   *
   * @param eventLoopGroup event loop group to use.
   * @param channelClass channel class to use, should be compatible with the event loop.
   * @return Builder that is set up with a {@link ServerBootstrap}.
   */
  public static Builder builder(
      EventLoopGroup eventLoopGroup, Class<? extends ServerChannel> channelClass) {
    ServerBootstrap serverBootstrap =
        new ServerBootstrap()
            .group(eventLoopGroup)
            .channel(channelClass)
            .childHandler(new Initializer());
    return new Builder(serverBootstrap);
  }

  static class Builder {
    private ServerBootstrap serverBootstrap;

    private AddressResolver addressResolver = AddressResolver.defaultResolver;

    private static long DEFAULT_BIND_TIMEOUT_IN_NANOS =
        TimeUnit.NANOSECONDS.convert(10, TimeUnit.SECONDS);

    private long bindTimeoutInNanos = DEFAULT_BIND_TIMEOUT_IN_NANOS;

    Builder(ServerBootstrap initialBootstrap) {
      this.serverBootstrap = initialBootstrap;
    }

    /**
     * Sets the bind timeout which is the amount of time allowed while binding listening interfaces
     * for nodes when establishing a cluster.
     *
     * @param time The amount of time to wait.
     * @param timeUnit The unit of time to wait in.
     * @return This builder.
     */
    public Builder withBindTimeout(long time, TimeUnit timeUnit) {
      this.bindTimeoutInNanos = TimeUnit.NANOSECONDS.convert(time, timeUnit);
      return this;
    }

    /**
     * Sets the address resolver to use when assigning {@link SocketAddress} to {@link Node}'s that
     * don't have previously provided addresses.
     *
     * @param addressResolver resolver to use.
     * @return This builder.
     */
    public Builder withAddressResolver(AddressResolver addressResolver) {
      this.addressResolver = addressResolver;
      return this;
    }

    /** @return a {@link Server} instance based on this builder's configuration. */
    public Server build() {
      return new Server(addressResolver, serverBootstrap, bindTimeoutInNanos);
    }
  }

  private static class RequestHandler extends ChannelInboundHandlerAdapter {

    private BoundNode node;
    private FrameCodec<ByteBuf> frameCodec;

    private RequestHandler(BoundNode node, FrameCodec<ByteBuf> frameCodec) {
      this.node = node;
      this.frameCodec = frameCodec;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      MDC.put("node", node.getId().toString());

      try {
        @SuppressWarnings("unchecked")
        Frame frame = (Frame) msg;
        node.handle(ctx, frame, frameCodec);
      } finally {
        MDC.remove("node");
      }
    }
  }

  static class Initializer extends ChannelInitializer<Channel> {

    @Override
    protected void initChannel(Channel channel) throws Exception {
      ChannelPipeline pipeline = channel.pipeline();
      BoundNode node = channel.parent().attr(HANDLER).get();
      MDC.put("node", node.getId().toString());

      try {
        logger.info("Got new connection {}", channel);

        pipeline
            .addLast("decoder", new FrameDecoder(frameCodec))
            .addLast("requestHandler", new RequestHandler(node, frameCodec));
      } finally {
        MDC.remove("node");
      }
    }
  }
}
