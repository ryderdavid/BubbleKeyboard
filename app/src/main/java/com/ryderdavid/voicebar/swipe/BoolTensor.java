package com.ryderdavid.voicebar.swipe;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.pytorch.executorch.DType;
import org.pytorch.executorch.Tensor;

/**
 * A boolean tensor (the encoder's key mask). ExecuTorch's Java API has the type but no way to make
 * one, and Tensor's constructor is Kotlin-internal, so this lives in Java: the native side reads the
 * dtype code and this direct buffer, one byte per value.
 */
final class BoolTensor extends Tensor {
    private final ByteBuffer data;

    BoolTensor(boolean[] values, long[] shape) {
        super(shape);
        data = ByteBuffer.allocateDirect(values.length).order(ByteOrder.nativeOrder());
        for (boolean v : values) data.put((byte) (v ? 1 : 0));
        data.rewind();
    }

    @Override public DType dtype() { return DType.BOOL; }

    @Override public Buffer getRawDataBuffer() { return data; }
}
