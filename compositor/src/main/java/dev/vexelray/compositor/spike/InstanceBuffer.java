package dev.vexelray.compositor.spike;

import dev.supirvast.vulkan.VulkanDevice;
import dev.vexelray.vulkan.present.StorageBuffer;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/**
 * The compositor's retained instances on the GPU: a host-visible {@link StorageBuffer} read by the instance vertex
 * shader through {@link VertexStorageBinding}, written in ranges from any offset of a source array.
 *
 * <p>Spike shim: the buffer handle and mapping are reached by reflection, because {@code StorageBuffer} binds for the
 * fragment stage only and copies from the start of a source array only. Both belong in vexelray-vulkan (gap G1).
 */
final class InstanceBuffer implements AutoCloseable {

    private final StorageBuffer storage;
    private final VertexStorageBinding binding;
    private final MemorySegment mapped;
    private long bytesWritten;

    InstanceBuffer(VulkanDevice device, int capacityFloats) {
        this.storage = new StorageBuffer(device, capacityFloats, 0);
        try {
            var b = StorageBuffer.class.getDeclaredField("buffer");
            b.setAccessible(true);
            var m = StorageBuffer.class.getDeclaredField("mapped");
            m.setAccessible(true);
            this.binding = new VertexStorageBinding(device, b.getLong(storage), 0);
            this.mapped = (MemorySegment) m.get(storage);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("StorageBuffer no longer has the fields the spike reaches into", e);
        }
    }

    /** Copy {@code floats} floats from {@code src[from]} to float {@code at} of the buffer. */
    void write(int at, float[] src, int from, int floats) {
        if (floats <= 0) {
            return;
        }
        MemorySegment.copy(src, from, mapped, JAVA_FLOAT, (long) at * Float.BYTES, floats);
        bytesWritten += (long) floats * Float.BYTES;
    }

    /** A control value (screen size, a window origin): not counted as instance upload. */
    void setUncounted(int at, float value) {
        mapped.setAtIndex(JAVA_FLOAT, at, value);
    }

    /** Bytes written since the last call: the upload cost of a frame. */
    long takeBytesWritten() {
        long b = bytesWritten;
        bytesWritten = 0;
        return b;
    }

    long descriptorSetLayout() {
        return binding.descriptorSetLayout();
    }

    long descriptorSet() {
        return binding.descriptorSet();
    }

    @Override
    public void close() {
        binding.close();
        storage.close();
    }
}
