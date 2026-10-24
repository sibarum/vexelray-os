package dev.vexelray.compositor.spike;

import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VkStructs;
import dev.supirvast.vulkan.VulkanDevice;
import dev.vexelray.vulkan.present.SampledColorTarget;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.List;

import static dev.supirvast.vulkan.Ffm.check;
import static dev.supirvast.vulkan.Ffm.invoke;
import static dev.supirvast.vulkan.Ffm.invokeVoid;
import static dev.supirvast.vulkan.Ffm.sa;
import static dev.supirvast.vulkan.Ffm.sf;
import static dev.supirvast.vulkan.Ffm.si;
import static dev.supirvast.vulkan.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The composite: the only retained image (design.md section 4). A {@link SampledColorTarget} for the image, its
 * framebuffer and its sampling descriptor, drawn into through a render pass of this class's own that <b>loads</b>
 * the previous contents, so a frame redraws only its damage.
 *
 * <p>Spike shim for gap G7. {@code SampledColorTarget}'s own pass clears every time; a load-op pass is
 * render-pass-compatible with it (load and store ops are not part of compatibility), so its framebuffer is reused,
 * reached by reflection. The fix belongs in vexelray-vulkan.
 *
 * <p>Each {@link #raster} is its own submission, waited on with a fence, so its cost is measured on the CPU clock
 * and the image is complete before the engine's frame samples it.
 */
public final class Composite implements AutoCloseable {

    /** A damage rect in composite px. */
    public record Rect(int x, int y, int w, int h) {
        public Rect intersect(Rect o) {
            int x0 = Math.max(x, o.x);
            int y0 = Math.max(y, o.y);
            int x1 = Math.min(x + w, o.x + o.w);
            int y1 = Math.min(y + h, o.y + o.h);
            return x1 > x0 && y1 > y0 ? new Rect(x0, y0, x1 - x0, y1 - y0) : null;
        }

        public Rect union(Rect o) {
            int x0 = Math.min(x, o.x);
            int y0 = Math.min(y, o.y);
            return new Rect(x0, y0, Math.max(x + w, o.x + o.w) - x0, Math.max(y + h, o.y + o.h) - y0);
        }

        public long area() {
            return (long) w * h;
        }
    }

    /** Records the draws for one damage rect. */
    public interface Drawer {
        void draw(MemorySegment cmd, Rect rect);
    }

    /** {@code VK_ATTACHMENT_LOAD_OP_LOAD}; supirvast's {@code Vk} has only the ops vexelray uses. */
    private static final int ATTACHMENT_LOAD_OP_LOAD = 0;

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor SET_VIEWPORT_SCISSOR =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);

    private static final GroupLayout ATTACHMENT_DESCRIPTION = MemoryLayout.structLayout(
            JAVA_INT.withName("flags"), JAVA_INT.withName("format"), JAVA_INT.withName("samples"),
            JAVA_INT.withName("loadOp"), JAVA_INT.withName("storeOp"), JAVA_INT.withName("stencilLoadOp"),
            JAVA_INT.withName("stencilStoreOp"), JAVA_INT.withName("initialLayout"), JAVA_INT.withName("finalLayout")
    ).withName("VkAttachmentDescription");

    private static final GroupLayout ATTACHMENT_REFERENCE = MemoryLayout.structLayout(
            JAVA_INT.withName("attachment"), JAVA_INT.withName("layout")).withName("VkAttachmentReference");

    private static final GroupLayout SUBPASS_DESCRIPTION = MemoryLayout.structLayout(
            JAVA_INT.withName("flags"), JAVA_INT.withName("pipelineBindPoint"), JAVA_INT.withName("inputAttachmentCount"),
            MemoryLayout.paddingLayout(4), ADDRESS.withName("pInputAttachments"),
            JAVA_INT.withName("colorAttachmentCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pColorAttachments"), ADDRESS.withName("pResolveAttachments"),
            ADDRESS.withName("pDepthStencilAttachment"), JAVA_INT.withName("preserveAttachmentCount"),
            MemoryLayout.paddingLayout(4), ADDRESS.withName("pPreserveAttachments")).withName("VkSubpassDescription");

    private static final GroupLayout SUBPASS_DEPENDENCY = MemoryLayout.structLayout(
            JAVA_INT.withName("srcSubpass"), JAVA_INT.withName("dstSubpass"), JAVA_INT.withName("srcStageMask"),
            JAVA_INT.withName("dstStageMask"), JAVA_INT.withName("srcAccessMask"), JAVA_INT.withName("dstAccessMask"),
            JAVA_INT.withName("dependencyFlags")).withName("VkSubpassDependency");

    private static final GroupLayout RENDER_PASS_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("attachmentCount"), ADDRESS.withName("pAttachments"),
            JAVA_INT.withName("subpassCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pSubpasses"),
            JAVA_INT.withName("dependencyCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pDependencies")
    ).withName("VkRenderPassCreateInfo");

    private static final GroupLayout COMMAND_POOL_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("queueFamilyIndex")).withName("VkCommandPoolCreateInfo");

    private static final GroupLayout FENCE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4)).withName("VkFenceCreateInfo");

    private static final GroupLayout COMMAND_BUFFER_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("commandPool"), JAVA_INT.withName("level"), JAVA_INT.withName("commandBufferCount")
    ).withName("VkCommandBufferAllocateInfo");

    private static final GroupLayout COMMAND_BUFFER_BEGIN_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pInheritanceInfo")
    ).withName("VkCommandBufferBeginInfo");

    private static final GroupLayout RENDER_PASS_BEGIN_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("renderPass"), JAVA_LONG.withName("framebuffer"),
            JAVA_INT.withName("area_offset_x"), JAVA_INT.withName("area_offset_y"),
            JAVA_INT.withName("area_extent_width"), JAVA_INT.withName("area_extent_height"),
            JAVA_INT.withName("clearValueCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pClearValues")
    ).withName("VkRenderPassBeginInfo");

    private static final GroupLayout SUBMIT_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pWaitSemaphores"),
            ADDRESS.withName("pWaitDstStageMask"), JAVA_INT.withName("commandBufferCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pCommandBuffers"), JAVA_INT.withName("signalSemaphoreCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pSignalSemaphores")).withName("VkSubmitInfo");

    private final VulkanDevice device;
    private final SampledColorTarget target;
    private final long framebuffer;
    private final long loadPass;
    private final long pool;
    private final MemorySegment cmd;
    private final long fence;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment pFence;
    private final MemorySegment scissor;

    private final MethodHandle vkDestroyRenderPass;
    private final MethodHandle vkDestroyCommandPool;
    private final MethodHandle vkDestroyFence;
    private final MethodHandle vkResetCommandBuffer;
    private final MethodHandle vkBeginCommandBuffer;
    private final MethodHandle vkEndCommandBuffer;
    private final MethodHandle vkCmdBeginRenderPass;
    private final MethodHandle vkCmdEndRenderPass;
    private final MethodHandle vkCmdSetViewport;
    private final MethodHandle vkCmdSetScissor;
    private final MethodHandle vkQueueSubmit;
    private final MethodHandle vkWaitForFences;
    private final MethodHandle vkResetFences;

    public Composite(VulkanDevice device, int width, int height) {
        this.device = device;
        this.target = new SampledColorTarget(device, width, height);
        try {
            var f = SampledColorTarget.class.getDeclaredField("framebuffer");
            f.setAccessible(true);
            this.framebuffer = f.getLong(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SampledColorTarget no longer has the framebuffer the spike reuses", e);
        }
        MemorySegment dev = device.handle();
        MethodHandle vkCreateRenderPass = device.command("vkCreateRenderPass", C4);
        this.vkDestroyRenderPass = device.command("vkDestroyRenderPass", D_LONG);
        MethodHandle vkCreateCommandPool = device.command("vkCreateCommandPool", C4);
        this.vkDestroyCommandPool = device.command("vkDestroyCommandPool", D_LONG);
        MethodHandle vkAllocateCommandBuffers = device.command("vkAllocateCommandBuffers",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkCreateFence = device.command("vkCreateFence", C4);
        this.vkDestroyFence = device.command("vkDestroyFence", D_LONG);
        this.vkResetCommandBuffer = device.command("vkResetCommandBuffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        this.vkBeginCommandBuffer = device.command("vkBeginCommandBuffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        this.vkEndCommandBuffer = device.command("vkEndCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        this.vkCmdBeginRenderPass = device.command("vkCmdBeginRenderPass",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
        this.vkCmdEndRenderPass = device.command("vkCmdEndRenderPass", FunctionDescriptor.ofVoid(ADDRESS));
        this.vkCmdSetViewport = device.command("vkCmdSetViewport", SET_VIEWPORT_SCISSOR);
        this.vkCmdSetScissor = device.command("vkCmdSetScissor", SET_VIEWPORT_SCISSOR);
        this.vkQueueSubmit = device.command("vkQueueSubmit",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));
        this.vkWaitForFences = device.command("vkWaitForFences",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));
        this.vkResetFences = device.command("vkResetFences", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

        try (Arena a = Arena.ofConfined()) {
            MemorySegment att = a.allocate(ATTACHMENT_DESCRIPTION);
            si(att, ATTACHMENT_DESCRIPTION, "format", Vk.FORMAT_R8G8B8A8_UNORM);
            si(att, ATTACHMENT_DESCRIPTION, "samples", Vk.SAMPLE_COUNT_1_BIT);
            si(att, ATTACHMENT_DESCRIPTION, "loadOp", ATTACHMENT_LOAD_OP_LOAD);
            si(att, ATTACHMENT_DESCRIPTION, "storeOp", Vk.ATTACHMENT_STORE_OP_STORE);
            si(att, ATTACHMENT_DESCRIPTION, "stencilLoadOp", Vk.ATTACHMENT_LOAD_OP_DONT_CARE);
            si(att, ATTACHMENT_DESCRIPTION, "stencilStoreOp", Vk.ATTACHMENT_STORE_OP_DONT_CARE);
            si(att, ATTACHMENT_DESCRIPTION, "initialLayout", Vk.IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            si(att, ATTACHMENT_DESCRIPTION, "finalLayout", Vk.IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            MemorySegment ref = a.allocate(ATTACHMENT_REFERENCE);
            si(ref, ATTACHMENT_REFERENCE, "attachment", 0);
            si(ref, ATTACHMENT_REFERENCE, "layout", Vk.IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            MemorySegment sub = a.allocate(SUBPASS_DESCRIPTION);
            si(sub, SUBPASS_DESCRIPTION, "pipelineBindPoint", Vk.PIPELINE_BIND_POINT_GRAPHICS);
            si(sub, SUBPASS_DESCRIPTION, "colorAttachmentCount", 1);
            sa(sub, SUBPASS_DESCRIPTION, "pColorAttachments", ref);
            MemorySegment deps = a.allocate(SUBPASS_DEPENDENCY, 2);
            MemorySegment d0 = deps.asSlice(0, SUBPASS_DEPENDENCY.byteSize());
            si(d0, SUBPASS_DEPENDENCY, "srcSubpass", Vk.SUBPASS_EXTERNAL);
            si(d0, SUBPASS_DEPENDENCY, "dstSubpass", 0);
            si(d0, SUBPASS_DEPENDENCY, "srcStageMask", Vk.PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
            si(d0, SUBPASS_DEPENDENCY, "dstStageMask", Vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            si(d0, SUBPASS_DEPENDENCY, "srcAccessMask", Vk.ACCESS_SHADER_READ_BIT);
            si(d0, SUBPASS_DEPENDENCY, "dstAccessMask", Vk.ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            MemorySegment d1 = deps.asSlice(SUBPASS_DEPENDENCY.byteSize(), SUBPASS_DEPENDENCY.byteSize());
            si(d1, SUBPASS_DEPENDENCY, "srcSubpass", 0);
            si(d1, SUBPASS_DEPENDENCY, "dstSubpass", Vk.SUBPASS_EXTERNAL);
            si(d1, SUBPASS_DEPENDENCY, "srcStageMask", Vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            si(d1, SUBPASS_DEPENDENCY, "dstStageMask", Vk.PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
            si(d1, SUBPASS_DEPENDENCY, "srcAccessMask", Vk.ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            si(d1, SUBPASS_DEPENDENCY, "dstAccessMask", Vk.ACCESS_SHADER_READ_BIT);
            MemorySegment rp = a.allocate(RENDER_PASS_CREATE_INFO);
            si(rp, RENDER_PASS_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO);
            si(rp, RENDER_PASS_CREATE_INFO, "attachmentCount", 1);
            sa(rp, RENDER_PASS_CREATE_INFO, "pAttachments", att);
            si(rp, RENDER_PASS_CREATE_INFO, "subpassCount", 1);
            sa(rp, RENDER_PASS_CREATE_INFO, "pSubpasses", sub);
            si(rp, RENDER_PASS_CREATE_INFO, "dependencyCount", 2);
            sa(rp, RENDER_PASS_CREATE_INFO, "pDependencies", deps);
            MemorySegment p = a.allocate(JAVA_LONG);
            check(invoke(vkCreateRenderPass, dev, rp, MemorySegment.NULL, p), "vkCreateRenderPass");
            this.loadPass = p.get(JAVA_LONG, 0);

            MemorySegment poolInfo = a.allocate(COMMAND_POOL_CREATE_INFO);
            si(poolInfo, COMMAND_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            si(poolInfo, COMMAND_POOL_CREATE_INFO, "flags", Vk.COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            si(poolInfo, COMMAND_POOL_CREATE_INFO, "queueFamilyIndex", device.queueFamilyIndex());
            check(invoke(vkCreateCommandPool, dev, poolInfo, MemorySegment.NULL, p), "vkCreateCommandPool");
            this.pool = p.get(JAVA_LONG, 0);
            MemorySegment cbAlloc = a.allocate(COMMAND_BUFFER_ALLOCATE_INFO);
            si(cbAlloc, COMMAND_BUFFER_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            sl(cbAlloc, COMMAND_BUFFER_ALLOCATE_INFO, "commandPool", pool);
            si(cbAlloc, COMMAND_BUFFER_ALLOCATE_INFO, "level", Vk.COMMAND_BUFFER_LEVEL_PRIMARY);
            si(cbAlloc, COMMAND_BUFFER_ALLOCATE_INFO, "commandBufferCount", 1);
            MemorySegment pCmd = a.allocate(ADDRESS);
            check(invoke(vkAllocateCommandBuffers, dev, cbAlloc, pCmd), "vkAllocateCommandBuffers");
            this.cmd = pCmd.get(ADDRESS, 0);
            MemorySegment fenceInfo = a.allocate(FENCE_CREATE_INFO);
            si(fenceInfo, FENCE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FENCE_CREATE_INFO);
            check(invoke(vkCreateFence, dev, fenceInfo, MemorySegment.NULL, p), "vkCreateFence");
            this.fence = p.get(JAVA_LONG, 0);
        }
        this.pFence = arena.allocate(JAVA_LONG);
        pFence.set(JAVA_LONG, 0, fence);
        this.scissor = arena.allocate(VkStructs.RECT_2D);
    }

    public SampledColorTarget image() {
        return target;
    }

    /** The pass to build pipelines against; compatible with the target's own. */
    public long renderPass() {
        return loadPass;
    }

    public int width() {
        return target.width();
    }

    public int height() {
        return target.height();
    }

    /** Clear the whole image to one colour and leave it ready to sample: the desktop before anything is drawn. */
    public void clear(float r, float g, float b) {
        target.renderInto((c, w, h) -> {
        }, r, g, b, 1f);
    }

    /** Set the scissor inside a {@link Drawer}. */
    public void scissor(MemorySegment c, Rect r) {
        si(scissor, VkStructs.RECT_2D, "offset_x", r.x());
        si(scissor, VkStructs.RECT_2D, "offset_y", r.y());
        si(scissor, VkStructs.RECT_2D, "extent_width", r.w());
        si(scissor, VkStructs.RECT_2D, "extent_height", r.h());
        invokeVoid(vkCmdSetScissor, c, 0, 1, scissor);
    }

    /**
     * Redraw {@code rects}: one pass over their bounding box, the drawer called once per rect. Returns once the GPU
     * has finished.
     */
    public void raster(List<Rect> rects, Drawer drawer) {
        if (rects.isEmpty()) {
            return;
        }
        Rect box = rects.get(0);
        for (Rect r : rects) {
            box = box.union(r);
        }
        MemorySegment dev = device.handle();
        try (Arena a = Arena.ofConfined()) {
            check(invoke(vkResetCommandBuffer, cmd, 0), "vkResetCommandBuffer");
            MemorySegment begin = a.allocate(COMMAND_BUFFER_BEGIN_INFO);
            si(begin, COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            si(begin, COMMAND_BUFFER_BEGIN_INFO, "flags", Vk.COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(invoke(vkBeginCommandBuffer, cmd, begin), "vkBeginCommandBuffer");
            MemorySegment rpBegin = a.allocate(RENDER_PASS_BEGIN_INFO);
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
            sl(rpBegin, RENDER_PASS_BEGIN_INFO, "renderPass", loadPass);
            sl(rpBegin, RENDER_PASS_BEGIN_INFO, "framebuffer", framebuffer);
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "area_offset_x", box.x());
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "area_offset_y", box.y());
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "area_extent_width", box.w());
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "area_extent_height", box.h());
            invokeVoid(vkCmdBeginRenderPass, cmd, rpBegin, Vk.SUBPASS_CONTENTS_INLINE);
            MemorySegment viewport = a.allocate(VkStructs.VIEWPORT);
            sf(viewport, VkStructs.VIEWPORT, "width", width());
            sf(viewport, VkStructs.VIEWPORT, "height", height());
            sf(viewport, VkStructs.VIEWPORT, "maxDepth", 1f);
            invokeVoid(vkCmdSetViewport, cmd, 0, 1, viewport);
            for (Rect r : rects) {
                scissor(cmd, r);
                drawer.draw(cmd, r);
            }
            invokeVoid(vkCmdEndRenderPass, cmd);
            check(invoke(vkEndCommandBuffer, cmd), "vkEndCommandBuffer");
            MemorySegment pCmd = a.allocate(ADDRESS);
            pCmd.set(ADDRESS, 0, cmd);
            MemorySegment submit = a.allocate(SUBMIT_INFO);
            si(submit, SUBMIT_INFO, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
            si(submit, SUBMIT_INFO, "commandBufferCount", 1);
            sa(submit, SUBMIT_INFO, "pCommandBuffers", pCmd);
            check(invoke(vkResetFences, dev, 1, pFence), "vkResetFences");
            check(invoke(vkQueueSubmit, device.queue(), 1, submit, fence), "vkQueueSubmit");
            check(invoke(vkWaitForFences, dev, 1, pFence, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");
        }
    }

    @Override
    public void close() {
        MemorySegment dev = device.handle();
        invokeVoid(vkDestroyFence, dev, fence, MemorySegment.NULL);
        invokeVoid(vkDestroyCommandPool, dev, pool, MemorySegment.NULL);
        invokeVoid(vkDestroyRenderPass, dev, loadPass, MemorySegment.NULL);
        target.close();
        arena.close();
    }
}
