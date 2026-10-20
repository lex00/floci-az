package io.floci.az.core.tls;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TlsConfigSourceStorageSansTest {

    @Test
    void coversHostStyleStorageOnLocalhostByDefault() {
        assertEquals(List.of("*.blob.localhost", "*.dfs.localhost", "*.queue.localhost", "*.table.localhost"),
                TlsConfigSource.storageSans(""));
    }

    @Test
    void addsTheConfiguredSuffixWithoutItsPort() {
        assertEquals(List.of("*.blob.localhost", "*.dfs.localhost", "*.queue.localhost", "*.table.localhost",
                        "*.blob.floci-az", "*.dfs.floci-az", "*.queue.floci-az", "*.table.floci-az"),
                TlsConfigSource.storageSans("floci-az:4577"));
    }

    @Test
    void aLocalhostSuffixAddsNothingTwice() {
        assertEquals(4, TlsConfigSource.storageSans("localhost:4577").size());
    }
}
