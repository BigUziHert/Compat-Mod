package dev.caleb.emfattachments.test;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;
import traben.entity_model_features.models.animation.state.EMFState;
import traben.entity_model_features.models.parts.EMFModelPart;
import traben.entity_model_features.models.parts.EMFModelPartCustom;
import traben.entity_model_features.models.parts.EMFModelPartWithState;

/** Read-only actual-part matrix witness; does not call animation, transform, or render methods itself. */
public final class ArmorMeshProbe {
    private static final IdentityHashMap<ModelPart,Tracked> parts = new IdentityHashMap<>();
    private static final List<Map<String,Object>> events = new ArrayList<>();
    private static float frame = Float.NaN;
    private static int omitted;
    private static final Field CHILDREN = field("children"), CUBES = field("cubes");
    private static final Field POLYGONS = field(ModelPart.Cube.class,"polygons"),
            VERTICES = field(POLYGONS.getType().getComponentType(),"vertices"),
            POSITION = field(VERTICES.getType().getComponentType(),"pos"),
            U = field(VERTICES.getType().getComponentType(),"u"),
            V = field(VERTICES.getType().getComponentType(),"v");
    private static final Field[] BOUNDS = java.util.stream.Stream.of("minX","minY","minZ","maxX","maxY","maxZ")
            .map(name->field(ModelPart.Cube.class,name)).toArray(Field[]::new);
    private ArmorMeshProbe() {}

    public static void skin(LivingEntity entity, HumanoidModel<?> model) {
        beginFrame();
        trackTree(entity.getUUID(), "skin/leftArm", model.leftArm, model.getClass().getName());
        trackTree(entity.getUUID(), "skin/rightArm", model.rightArm, model.getClass().getName());
    }

    public static void armor(LivingEntity entity, EquipmentSlot slot, HumanoidModel<?> model) {
        beginFrame();
        trackTree(entity.getUUID(), "armor/"+slot.getName()+"/leftArm", model.leftArm, model.getClass().getName());
        trackTree(entity.getUUID(), "armor/"+slot.getName()+"/rightArm", model.rightArm, model.getClass().getName());
    }

    private static void beginFrame() {
        float current = EMFState.getFrameCounter();
        if (frame != current) { frame=current; parts.clear(); events.clear(); omitted=0; }
    }

    private static void trackTree(UUID uuid, String path, ModelPart part, String model) {
        parts.put(part,new Tracked(uuid,path,model));
        for (var child: children(part).entrySet()) trackTree(uuid,path+"/"+child.getKey(),child.getValue(),model);
    }

    public static void transformed(ModelPart part, PoseStack matrices) { record(part,matrices.last(),"after_translate_and_rotate"); }
    public static void compiling(ModelPart part, PoseStack.Pose pose) { record(part,pose,"compile_entry"); }

    private static void record(ModelPart part, PoseStack.Pose pose, String stage) {
        if (frame!=EMFState.getFrameCounter()) return;
        Tracked tracked=parts.get(part);if(tracked==null)return;
        var state=EMFState.state();
        if(state==null||!tracked.uuid.equals(state.uuid()))return;
        if(events.size()>=256){omitted++;return;}
        var mc=Minecraft.getInstance();var row=new LinkedHashMap<String,Object>();
        row.put("stage",stage);row.put("emfFrame",frame);row.put("uuid",tracked.uuid.toString());
        row.put("path",tracked.path);row.put("modelClass",tracked.model);row.put("partClass",part.getClass().getName());
        row.put("partIdentity",System.identityHashCode(part));
        row.put("camera",mc.options.getCameraType().name());row.put("shadow",ModList.get().isLoaded("iris")&&Shadow.active());
        row.put("playerAnimatorFirstPersonPass",dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode.isFirstPersonPass());
        row.put("isLocalPlayer",mc.player!=null&&tracked.uuid.equals(mc.player.getUUID()));
        row.put("isCameraEntity",mc.getCameraEntity()!=null&&tracked.uuid.equals(mc.getCameraEntity().getUUID()));
        row.put("isInHandItemLayerTransform",EMFState.isInHandItemLayerTransform);
        row.put("localBody",ModList.get().isLoaded("firstperson")&&mc.player!=null&&tracked.uuid.equals(mc.player.getUUID())
                &&dev.caleb.emfattachments.client.FirstPersonRenderPolicy.isLocalBody(mc.player));
        row.put("fields",List.of(part.x,part.y,part.z,part.xRot,part.yRot,part.zRot,part.xScale,part.yScale,part.zScale));
        row.put("visible",part.visible);row.put("skipDraw",part.skipDraw);
        row.put("cubeCount",cubes(part).size());row.put("children",List.copyOf(children(part).keySet()));
        if(stage.equals("compile_entry"))row.put("actualCubes",cubeGeometry(part));
        row.put("matrixColumnMajor",floats(pose.pose().get(new float[16])));
        if(part instanceof EMFModelPart emf) {
            row.put("emfRootIdentity",System.identityHashCode(emf.getRoot()));
            row.put("emfRootIsMainModel",emf.getRoot().isMainModel);
            row.put("emfPartSetByAnimation",emf.isSetByAnimation);
        }
        if(part instanceof EMFModelPartCustom custom) {row.put("emfCustomId",custom.id);row.put("emfAttach",custom.attach);}
        if(part instanceof EMFModelPartWithState variant)row.put("emfVariant",variant.currentModelVariant);
        events.add(row);
    }

    public static Map<String,Object> snapshot(UUID uuid) {
        if(frame!=EMFState.getFrameCounter())return Map.of("observed",false);
        return Map.of("observed",true,"emfFrame",frame,"events",events.stream().filter(e->uuid.toString().equals(e.get("uuid"))).map(LinkedHashMap::new).toList(),
                "omittedEvents",omitted,"scope","Actual transform-return and compile-entry witnesses; compilation entry is not a vertex-output assertion.");
    }

    private static List<Float> floats(float[] values) {var out=new ArrayList<Float>();for(float f:values)out.add(f);return out;}
    private static Field field(String name) {
        return field(ModelPart.class,name);
    }
    private static Field field(Class<?> owner,String name) {
        try {Field field=owner.getDeclaredField(name);field.setAccessible(true);return field;}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Read-only model witness field "+name,e);}
    }
    private static List<Map<String,Object>> cubeGeometry(ModelPart part) {
        try {
            var result=new ArrayList<Map<String,Object>>();
            for(Object cube:cubes(part)) {
                var row=new LinkedHashMap<String,Object>();row.put("class",cube.getClass().getName());
                var bounds=new ArrayList<Float>();for(Field field:BOUNDS)bounds.add(field.getFloat(cube));row.put("minMax",bounds);
                var polygons=new ArrayList<List<List<Float>>>();
                for(Object polygon:(Object[])POLYGONS.get(cube)) {
                    if(polygon==null)continue;
                    var vertices=new ArrayList<List<Float>>();
                    for(Object vertex:(Object[])VERTICES.get(polygon)) {
                        var xyz=(org.joml.Vector3f)POSITION.get(vertex);
                        vertices.add(List.of(xyz.x(),xyz.y(),xyz.z(),U.getFloat(vertex),V.getFloat(vertex)));
                    }
                    polygons.add(vertices);
                }
                row.put("polygonVerticesXYZUV",polygons);result.add(row);
            }
            return result;
        } catch(IllegalAccessException e){throw new IllegalStateException("Read-only actual cube geometry",e);}
    }
    @SuppressWarnings("unchecked") private static Map<String,ModelPart> children(ModelPart part) {
        try{return (Map<String,ModelPart>)CHILDREN.get(part);}catch(IllegalAccessException e){throw new IllegalStateException(e);}
    }
    private static List<?> cubes(ModelPart part) {
        try{return (List<?>)CUBES.get(part);}catch(IllegalAccessException e){throw new IllegalStateException(e);}
    }
    private record Tracked(UUID uuid,String path,String model){}
    private static final class Shadow {static boolean active(){return net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass();}}
}
