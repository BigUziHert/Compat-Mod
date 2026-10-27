package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Counts real emitted vertices without changing native rendering. */
public final class RagdollVertexConsumer implements VertexConsumer {
    private final VertexConsumer delegate;
    private final String key;
    public RagdollVertexConsumer(VertexConsumer delegate, String key) { this.delegate = delegate; this.key = key; }
    @Override public VertexConsumer addVertex(float x,float y,float z) { RagdollRenderCounters.vertex(key, x,y,z); delegate.addVertex(x,y,z); return this; }
    @Override public VertexConsumer setColor(int r,int g,int b,int a) { delegate.setColor(r,g,b,a); return this; }
    @Override public VertexConsumer setUv(float u,float v) { delegate.setUv(u,v); return this; }
    @Override public VertexConsumer setUv1(int u,int v) { delegate.setUv1(u,v); return this; }
    @Override public VertexConsumer setUv2(int u,int v) { delegate.setUv2(u,v); return this; }
    @Override public VertexConsumer setNormal(float x,float y,float z) { delegate.setNormal(x,y,z); return this; }
}
