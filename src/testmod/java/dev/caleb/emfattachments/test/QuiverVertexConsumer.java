package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Counts native QuiverLayer output separately from the Accessories renderer. */
public final class QuiverVertexConsumer implements VertexConsumer {
    private final VertexConsumer delegate;
    private final AttachmentTestMod.TestResult result;
    public QuiverVertexConsumer(VertexConsumer delegate, AttachmentTestMod.TestResult result) { this.delegate = delegate; this.result = result; }
    @Override public VertexConsumer addVertex(float x,float y,float z) { result.quiverVertices++; if (!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z)) result.quiverInvalidVertices++; delegate.addVertex(x,y,z); return this; }
    @Override public VertexConsumer setColor(int r,int g,int b,int a) { delegate.setColor(r,g,b,a); return this; }
    @Override public VertexConsumer setUv(float u,float v) { delegate.setUv(u,v); return this; }
    @Override public VertexConsumer setUv1(int u,int v) { delegate.setUv1(u,v); return this; }
    @Override public VertexConsumer setUv2(int u,int v) { delegate.setUv2(u,v); return this; }
    @Override public VertexConsumer setNormal(float x,float y,float z) { delegate.setNormal(x,y,z); return this; }
}
