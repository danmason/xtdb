package xtdb;

import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowFileWriter;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.CompletableFuture;

public interface IBufferPool extends AutoCloseable {
    CompletableFuture<ArrowBuf> getBuffer(Path key);

    CompletableFuture<?> putObject(Path k, ByteBuffer buffer);

    Iterable<Path> listObjects();

    Iterable<Path> listObjects(Path dir);

    WritableByteChannel openChannel(Path k);

    ArrowFileWriter openArrowFileWriter(Path k, VectorSchemaRoot vsr);
}
