/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.utils.accessor.IEntityRenderManager;
import baritone.utils.accessor.IRenderPipelines;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.awt.*;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;

public interface IRenderer {

    IRenderPipelines PIPELINES = (IRenderPipelines) new RenderPipelines();
    IEntityRenderManager renderManager = (IEntityRenderManager) Minecraft.getInstance().getEntityRenderDispatcher();
    Settings settings = BaritoneAPI.getSettings();

    RenderPipeline LINES_NO_DEPTH = PIPELINES.baritone$registerPipeline(
            RenderPipeline.builder(PIPELINES.getLinesSnippet())
                    .withLocation("pipeline/baritone_lines_no_depth")
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .build());

    RenderPipeline BEACON_BEAM_OPAQUE_NO_DEPTH = PIPELINES.baritone$registerPipeline(
            RenderPipeline.builder(PIPELINES.getBeaconBeamSnippet())
                    .withLocation("pipeline/baritone_beacon_beam_opaque")
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .build());

    RenderPipeline BEACON_BEAM_TRANSLUCENT_NO_DEPTH = PIPELINES.baritone$registerPipeline(
            RenderPipeline.builder(PIPELINES.getBeaconBeamSnippet())
                    .withLocation("pipeline/baritone_beacon_beam_translucent")
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
                    .build());

    float[] color = new float[]{1.0F, 1.0F, 1.0F, 255.0F};

    static void glColor(Color color, float alpha) {
        float[] colorComponents = color.getColorComponents(null);
        IRenderer.color[0] = colorComponents[0];
        IRenderer.color[1] = colorComponents[1];
        IRenderer.color[2] = colorComponents[2];
        IRenderer.color[3] = alpha;
    }

    static BufferBuilder startLines(Color color, float alpha) {
        glColor(color, alpha);
        return new BufferBuilder(VertexBuffer.BUFFER, PrimitiveTopology.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH);
    }

    static BufferBuilder startLines(Color color) {
        return startLines(color, .4f);
    }

    static void endLines(BufferBuilder bufferBuilder, boolean ignoreDepth) {
        MeshData meshData = bufferBuilder.build();
        if (meshData != null) {
            try (meshData) {
                render(meshData, ignoreDepth ? LINES_NO_DEPTH : RenderPipelines.LINES, null);
            }
        }
    }

    static BufferBuilder startBlockQuads() {
        return new BufferBuilder(VertexBuffer.BUFFER, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
    }

    static void endBeaconBuffer(BufferBuilder bufferBuilder, Identifier texture, boolean translucent, boolean ignoreDepth) {
        MeshData meshData = bufferBuilder.build();
        if (meshData != null) {
            try (meshData) {
                RenderPipeline pipeline = translucent
                        ? (ignoreDepth ? BEACON_BEAM_TRANSLUCENT_NO_DEPTH : RenderPipelines.BEACON_BEAM_TRANSLUCENT)
                        : (ignoreDepth ? BEACON_BEAM_OPAQUE_NO_DEPTH : RenderPipelines.BEACON_BEAM_OPAQUE);
                render(meshData, pipeline, texture);
            }
        }
    }

    static void render(MeshData meshData, RenderPipeline pipeline, Identifier texture) {
        MeshData.DrawState drawState = meshData.drawState();
        ByteBuffer vertexData = meshData.vertexBuffer();
        int size = vertexData.remaining();
        GpuBuffer vertexBuffer = VertexBuffer.upload(vertexData);
        RenderSystem.AutoStorageIndexBuffer indexBuffer = RenderSystem.getSequentialBuffer(drawState.primitiveTopology());
        GpuBuffer indices = indexBuffer.getBuffer(drawState.indexCount());
        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f());
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Baritone",
                mainTarget.getColorTextureView(), Optional.empty(),
                mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            if (texture != null) {
                AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(texture);
                pass.bindTexture("Sampler0", tex.getTextureView(), tex.getSampler());
            }
            pass.setVertexBuffer(0, vertexBuffer.slice(0L, size));
            pass.setIndexBuffer(indices, indexBuffer.type());
            pass.drawIndexed(drawState.indexCount(), 1, 0, 0, 0);
        }
    }

    static void emitLine(BufferBuilder bufferBuilder, PoseStack stack, double x1, double y1, double z1, double x2, double y2, double z2, float lineWidth) {
        final double dx = x2 - x1;
        final double dy = y2 - y1;
        final double dz = z2 - z1;

        final double invMag = 1.0 / Math.sqrt(dx * dx + dy * dy + dz * dz);
        final float nx = (float) (dx * invMag);
        final float ny = (float) (dy * invMag);
        final float nz = (float) (dz * invMag);

        emitLine(bufferBuilder, stack, x1, y1, z1, x2, y2, z2, nx, ny, nz, lineWidth);
    }

    static void emitLine(BufferBuilder bufferBuilder, PoseStack stack,
                         double x1, double y1, double z1,
                         double x2, double y2, double z2,
                         double nx, double ny, double nz,
                         float lineWidth
    ) {
        emitLine(bufferBuilder, stack,
                (float) x1, (float) y1, (float) z1,
                (float) x2, (float) y2, (float) z2,
                (float) nx, (float) ny, (float) nz,
                lineWidth
        );
    }

    static void emitLine(BufferBuilder bufferBuilder, PoseStack stack,
                         float x1, float y1, float z1,
                         float x2, float y2, float z2,
                         float nx, float ny, float nz,
                         float lineWidth
    ) {
        PoseStack.Pose pose = stack.last();

        bufferBuilder.addVertex(pose, x1, y1, z1).setColor(color[0], color[1], color[2], color[3]).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
        bufferBuilder.addVertex(pose, x2, y2, z2).setColor(color[0], color[1], color[2], color[3]).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
    }

    static void emitAABB(BufferBuilder bufferBuilder, PoseStack stack, AABB aabb, float lineWidth) {
        AABB toDraw = aabb.move(-renderManager.renderPosX(), -renderManager.renderPosY(), -renderManager.renderPosZ());

        // bottom
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.minY, toDraw.minZ, toDraw.maxX, toDraw.minY, toDraw.minZ, 1.0, 0.0, 0.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.minY, toDraw.minZ, toDraw.maxX, toDraw.minY, toDraw.maxZ, 0.0, 0.0, 1.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.minY, toDraw.maxZ, toDraw.minX, toDraw.minY, toDraw.maxZ, -1.0, 0.0, 0.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.minY, toDraw.maxZ, toDraw.minX, toDraw.minY, toDraw.minZ, 0.0, 0.0, -1.0, lineWidth);
        // top
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.maxY, toDraw.minZ, toDraw.maxX, toDraw.maxY, toDraw.minZ, 1.0, 0.0, 0.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.maxY, toDraw.minZ, toDraw.maxX, toDraw.maxY, toDraw.maxZ, 0.0, 0.0, 1.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.maxY, toDraw.maxZ, toDraw.minX, toDraw.maxY, toDraw.maxZ, -1.0, 0.0, 0.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.maxY, toDraw.maxZ, toDraw.minX, toDraw.maxY, toDraw.minZ, 0.0, 0.0, -1.0, lineWidth);
        // corners
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.minY, toDraw.minZ, toDraw.minX, toDraw.maxY, toDraw.minZ, 0.0, 1.0, 0.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.minY, toDraw.minZ, toDraw.maxX, toDraw.maxY, toDraw.minZ, 0.0, 1.0, 0.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.maxX, toDraw.minY, toDraw.maxZ, toDraw.maxX, toDraw.maxY, toDraw.maxZ, 0.0, 1.0, 0.0, lineWidth);
        emitLine(bufferBuilder, stack, toDraw.minX, toDraw.minY, toDraw.maxZ, toDraw.minX, toDraw.maxY, toDraw.maxZ, 0.0, 1.0, 0.0, lineWidth);
    }

    static void emitAABB(BufferBuilder bufferBuilder, PoseStack stack, AABB aabb, double expand, float lineWidth) {
        emitAABB(bufferBuilder, stack, aabb.inflate(expand, expand, expand), lineWidth);
    }

    static void emitLine(BufferBuilder bufferBuilder, PoseStack stack, Vec3 start, Vec3 end, float lineWidth) {
        double vpX = renderManager.renderPosX();
        double vpY = renderManager.renderPosY();
        double vpZ = renderManager.renderPosZ();
        emitLine(bufferBuilder, stack, start.x - vpX, start.y - vpY, start.z - vpZ, end.x - vpX, end.y - vpY, end.z - vpZ, lineWidth);
    }

    static void emitTexturedVertex(BufferBuilder bufferBuilder, PoseStack.Pose pose, float x, float y, float z, int color, float u, float v, float nx, float ny, float nz) {
        bufferBuilder.addVertex(pose, x, y, z)
                .setColor(color)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(15728880)
                .setNormal(pose, nx, ny, nz);
    }

    static Identifier beaconBeamTexture() {
        return BeaconRenderer.BEAM_LOCATION;
    }

    /**
     * Reusable CPU-side vertex staging buffer and its GPU counterpart. Replaces the removed
     * {@code Tesselator}/{@code RenderType.draw(MeshData)} pipeline from Minecraft 26.1.
     */
    final class VertexBuffer {
        static final ByteBufferBuilder BUFFER = new ByteBufferBuilder(262144);
        private static GpuBuffer cached;

        private VertexBuffer() {}

        static GpuBuffer upload(ByteBuffer data) {
            int size = data.remaining();
            if (cached == null || cached.size() < size) {
                if (cached != null) {
                    cached.close();
                }
                cached = RenderSystem.getDevice().createBuffer(() -> "Baritone vertex buffer", 40, size);
            }
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(cached.slice(0L, size), data);
            return cached;
        }
    }
}
