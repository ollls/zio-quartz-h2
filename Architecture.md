# ZIO Quartz H2 Architecture

## HTTP/2 Connection Flow Diagram

This document outlines the architecture of the HTTP/2 connection implementation in ZIO Quartz H2, with a focus on how ZIO Queues and ZStreams are utilized for efficient data flow management.

### Main Components

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           QuartzH2Server                                 │
└─────────────────────────────────────────┬─────────────────────────────────┘
                                    │ creates
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           Http2Connection                               │
│                                                                         │
│  ┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐  │
│  │  Outbound Queue │      │  Flow Control   │      │ Stream Table    │  │
│  │  (outq)         │◄────►│  Management     │◄────►│ (streamTbl)     │  │
│  └─────────────────┘      └─────────────────┘      └─────────────────┘  │
│           ▲                        ▲                        ▲            │
│           │                        │                        │            │
│           ▼                        ▼                        ▼            │
│  ┌─────────────────┐      ┌─────────────────┐      ┌─────────────────┐  │
│  │ Outbound Worker │      │ Packet Handler  │      │ Http2Stream(s)  │  │
│  │ Process         │◄────►│                 │◄────►│                 │  │
│  └─────────────────┘      └─────────────────┘      └─────────────────┘  │
│                                    ▲                        ▲            │
└────────────────────────────────────┼────────────────────────┼────────────┘
                                     │                        │
                                     ▼                        ▼
┌──────────────────────────────────┐    ┌──────────────────────────┐
│         IOChannel                 │    │      Http Route          │
└──────────────────────────────────┘    └──────────────────────────┘
```

## Functional Stream Processing with ZStream

ZIO Quartz H2 leverages ZIO's functional streams (ZStream) as a core abstraction for processing HTTP/2 data. Unlike traditional imperative streams, ZStream represents a *description* of data transformations that are executed only when the stream is run. This functional approach provides several benefits:

1. **Composability**: Stream transformations can be composed together without side effects
2. **Resource Safety**: Resources are properly acquired and released
3. **Backpressure**: Automatic handling of backpressure throughout the pipeline
4. **Error Handling**: Structured error handling within the stream processing pipeline

### Key ZStream Transformations

- **`makePacketStream`**: Creates a ZStream that reads from the IOChannel and transforms raw bytes into HTTP/2 packets
- **`packetStreamPipe`**: A ZPipeline that transforms a stream of bytes into a stream of HTTP/2 frames
- **`dataEvalEffectProducer`**: Produces data from queues as a ZStream transformation

## ZIO Queue Usage

The HTTP/2 connection implementation in ZIO Quartz H2 makes extensive use of ZIO Queues for managing data flow between different components. Here are the key queues used in the system:

### 1. Outbound Queue (`outq`)

- **Purpose**: Manages outgoing data packets (ByteBuffers) to be sent to the client
- **Type**: `Queue[ByteBuffer]`
- **Capacity**: Bounded queue with capacity of 1024
- **Creation**: Created during the initialization of an HTTP/2 connection
- **Usage**: All outgoing frames are offered to this queue and processed by the outbound worker

### 2. Stream Flow Control Queues

Each HTTP/2 stream has several queues to manage its data flow:

#### a. Data Input Queue (`inDataQ`)

- **Purpose**: Accumulates incoming data packets for a specific stream
- **Type**: `Queue[ByteBuffer]`
- **Capacity**: Unbounded queue
- **Creation**: Created when a new stream is opened
- **Usage**: Stores incoming DATA frames for processing by the application

#### b. Flow Control Sync Queue (`outXFlowSync`)

- **Purpose**: Synchronizes flow control for outgoing data frames
- **Type**: `Queue[Boolean]`
- **Capacity**: Unbounded queue
- **Creation**: Created when a new stream is opened
- **Usage**: Signals when the stream can send more data based on flow control window updates

#### c. Window Update Sync Queue (`syncUpdateWindowQ`)

- **Purpose**: Manages window update synchronization
- **Type**: `Queue[Unit]`
- **Capacity**: Dropping queue with capacity of 1
- **Creation**: Created when a new stream is opened
- **Usage**: Coordinates window updates for flow control

## Data Flow Process

### Incoming Data Flow

1. **Data Reception as ZStream**:
   - The `processIncoming` method sets up a ZStream pipeline starting with leftover data
   - `makePacketStream` creates a ZStream that reads from the IOChannel and transforms raw bytes into HTTP/2 packets
   - This is a *description* of the transformation, not the actual execution

2. **Stream Transformation**:
   - The byte stream is transformed via the `packetStreamPipe` ZPipeline
   - This pipeline chunks the bytes into proper HTTP/2 frames
   - The transformation is applied lazily when the stream is consumed

3. **Stream Consumption**:
   - The transformed stream is consumed with `foreach`, which applies `packet_handler` to each packet
   - Only at this point is the actual I/O performed and frames processed

4. **Packet Handling**:
   - Each packet is processed by the `packet_handler` method
   - Frames are parsed and handled according to their type (HEADERS, DATA, SETTINGS, etc.)

5. **Stream Data Processing**:
   - For DATA frames, the data is placed in the appropriate stream's `inDataQ`
   - Flow control is managed by updating window sizes and sending WINDOW_UPDATE frames when necessary

### Outgoing Data Flow

1. **Frame Generation**:
   - Outgoing frames are created using methods like `headerFrame` and `dataFrame`
   - These frames are offered to the `outq` queue

2. **Outbound Processing**:
   - The `outBoundWorkerProc` continuously takes frames from the `outq` queue
   - It writes the frames to the IOChannel for transmission to the client

3. **Flow Control Management**:
   - Before sending DATA frames, the system checks both global and stream-specific flow control windows
   - The `txWindow_Transmit` method handles the logic for splitting frames if necessary based on available window size
   - The `outXFlowSync` queue is used to signal when more data can be sent after window updates

4. **Response Streaming**:
   - When sending response bodies, data is often provided as a ZStream
   - This allows for efficient streaming of large responses without loading everything into memory
   - The ZStream is consumed and transformed into HTTP/2 DATA frames as needed

## Key Components

### Http2Connection

The main class that manages an HTTP/2 connection. It extends `Http2ConnectionCommon` and implements the core HTTP/2 protocol logic.

### Http2ConnectionCommon

A trait that provides common functionality for HTTP/2 connections, including methods for creating and sending frames.

### Http2Stream

Represents an individual HTTP/2 stream within a connection. Each stream has its own set of queues for managing data flow.

### Flow Control

HTTP/2 implements flow control at two levels:

1. **Connection-Level**: Managed by `globalTransmitWindow` and `globalInboundWindow`
2. **Stream-Level**: Each stream has its own `transmitWindow` and `inboundWindow`

ZIO Queues play a crucial role in coordinating these flow control mechanisms, ensuring efficient data transmission while preventing buffer bloat and resource exhaustion.
