package project.study.metrics.amplitude;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** 읽은 바이트가 상한을 넘으면 {@link ExportTooLargeException}을 던진다 — 압축 해제 전(전송 바이트) 기준. */
final class LimitedInputStream extends FilterInputStream {

    private final long limitBytes;
    private long readBytes;

    LimitedInputStream(InputStream in, long limitBytes) {
        super(in);
        this.limitBytes = limitBytes;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value != -1) {
            count(1);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read > 0) {
            count(read);
        }
        return read;
    }

    long readBytes() {
        return readBytes;
    }

    private void count(long bytes) {
        readBytes += bytes;
        if (readBytes > limitBytes) {
            throw new ExportTooLargeException(limitBytes);
        }
    }
}
