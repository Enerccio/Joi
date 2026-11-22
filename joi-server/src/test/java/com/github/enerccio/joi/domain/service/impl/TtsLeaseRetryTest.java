package com.github.enerccio.joi.domain.service.impl;

import com.github.enerccio.marginalia.domain.service.gpu.GpuException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which failures of a GPU lock's step make the speech call try once more: a connection that broke, nothing else. */
class TtsLeaseRetryTest {

    @Test
    void aClosedConnectionIsTriedAgain() {
        GpuException step = new GpuException("The step failed: HTTP/1.1 header parser received no bytes", new IOException("HTTP/1.1 header parser received no bytes"));
        // the arbiter's runAll wraps the step's failure with its position
        assertTrue(TtsServiceImpl.connectionDropped(new GpuException("Step 1 of 2: " + step.getMessage(), step)));
    }

    @Test
    void aTimeoutIsNot() {
        GpuException step = new GpuException("The step failed: request timed out", new HttpTimeoutException("request timed out"));
        assertFalse(TtsServiceImpl.connectionDropped(new GpuException("Step 1 of 2: " + step.getMessage(), step)));
    }

    @Test
    void aStepThatAnsweredAnErrorIsNot() {
        assertFalse(TtsServiceImpl.connectionDropped(new GpuException("The step \"Ensure Chatterbox\" answered 500")));
    }

    @Test
    void aCancelIsNot() {
        assertFalse(TtsServiceImpl.connectionDropped(GpuException.cancelled()));
    }
}
