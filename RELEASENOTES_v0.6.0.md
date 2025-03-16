# Quartz H2 Server v6.0.0 Release Notes

![Quartz H2 Server](https://img.shields.io/badge/Quartz%20H2-v6.0.0-blue)

## 🚀 New Feature: Linux IO-Uring Integration

We're excited to announce that Quartz H2 Server now offers Linux IO-Uring support as an additional option for high-performance asynchronous I/O operations. This enhancement provides users with a choice between the existing Java NIO implementation and the new native Linux IO-Uring support, allowing for flexibility based on deployment environment and performance requirements.

### Key Improvements

#### 🔄 Choice of I/O Implementations
- **Java NIO**: Continue using the existing implementation for cross-platform compatibility
- **Linux IO-Uring**: Opt for the new high-performance native implementation on Linux systems
- Both options are fully supported, giving users the flexibility to choose based on their specific needs

#### 🔢 Multiple IO-Uring Instances
- The server supports configurable multiple IO-Uring rings through the `IoUringTbl` implementation
- Each ring operates independently, allowing for better distribution of network connections
- Connections are automatically distributed to the least loaded ring via a reference counting mechanism
- Configure the number of rings with `NUMBER_OF_RING_INSTANCES` parameter to match your workload

#### ⚙️ Recommended ZIO Runtime Configuration
- We recommend the following ZIO Runtime configuration for optimal performance with IO-Uring:
  ```scala
  override val bootstrap = zio.Runtime.removeDefaultLoggers ++ SLF4J.slf4j ++ zio.Runtime.setExecutor(
    zio.Executor.fromJavaExecutor(Executors.newWorkStealingPool(Runtime.getRuntime().availableProcessors() - NUMBER_OF_RING_INSTANCES))
  ) ++ zio.Runtime.setBlockingExecutor(zio.Executor.fromJavaExecutor(Executors.newCachedThreadPool()))
  ```
- This ensures optimal resource allocation between CPU-bound tasks and IO operations
- An example of this configuration can be found in the examples folder (not part of the core release)

#### 🔄 Asynchronous Network Operations
- New `IOURingChannel` implementation provides fully asynchronous integration with IO-Uring
- Non-blocking read and write operations for maximum throughput
- Efficient direct buffer management for reduced memory overhead
- Supports both TLS (`run4`) and plain connections (`run5`)

## 🔒 Security and Dependency Updates

- **Scala 3.3**: Upgraded to the latest version for improved performance and language features
- **logback-classic 1.5.17**: Updated to address necessary security vulnerabilities
- These upgrades ensure the server remains secure and up-to-date with the latest improvements

## 🔧 Technical Details

- The `IoUringTbl` class manages a collection of IO-Uring instances and distributes connections across them
- Each connection is handled by a dedicated `IOURingChannel` that integrates with the IO-Uring API

## 📋 Usage

Users can choose their preferred I/O implementation:

- For Java NIO (original implementation):
  ```scala
  server.start(...)
  ```

- For Linux IO-Uring:
  ```scala
  server.startIO_linuxOnly(NUMBER_OF_RING_INSTANCES, R, filter)
  ```

---

This release expands our server's capabilities by offering an additional high-performance I/O option while maintaining compatibility with the existing implementation, along with important security updates.
