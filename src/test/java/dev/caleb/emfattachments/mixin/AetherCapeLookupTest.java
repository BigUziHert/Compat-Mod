package dev.caleb.emfattachments.mixin;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.*;

class AetherCapeLookupTest {
    private static final String INVENTORY = "io/wispforest/accessories/impl/ExpandedSimpleContainer";

    @Test void installedCapeLookupHasTwoParallelFindFirstCallsWithBothInventoryLocalsAvailable() throws Exception {
        var method = classNode("com/aetherteam/aether/mixin/AetherMixinHooks").methods.stream()
                .filter(m -> m.name.equals("isCapeVisible") && m.desc.equals(
                        "(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;"))
                .findFirst().orElseThrow();
        var calls = Arrays.stream(method.instructions.toArray())
                .filter(i -> i instanceof MethodInsnNode call && call.owner.equals("java/util/stream/StreamSupport")
                        && call.name.equals("stream")
                        && call.desc.equals("(Ljava/util/Spliterator;Z)Ljava/util/stream/Stream;"))
                .map(i -> (MethodInsnNode) i).toList();
        assertEquals(2, calls.size(), "Actual and cosmetic lookups must both be intercepted");
        var locals = method.localVariables.stream().filter(v -> v.desc.equals("L" + INVENTORY + ";"))
                .sorted(java.util.Comparator.comparingInt(v -> v.index)).toList();
        assertEquals(2, locals.size());
        assertEquals(List.of(3, 4), locals.stream().map(v -> v.index).toList());
        for (var call : calls) {
            assertEquals(Opcodes.ICONST_1, previousInstruction(call).getOpcode());
            assertInstanceOf(MethodInsnNode.class, nextInstruction(call));
            var terminal = (MethodInsnNode) nextInstruction(call);
            assertEquals("java/util/stream/Stream", terminal.owner);
            assertEquals("findFirst", terminal.name);
            int position = method.instructions.indexOf(call);
            for (var local : locals) {
                assertTrue(method.instructions.indexOf(local.start) <= position);
                assertTrue(position < method.instructions.indexOf(local.end));
            }
        }
        // No custom spliterator overrides the unknown-size iterator that caused
        // the allocation. Check the native contract rather than inventing a size.
        var inventory = classNode(INVENTORY);
        assertTrue(inventory.interfaces.contains("java/lang/Iterable"));
        assertFalse(inventory.methods.stream().anyMatch(m -> m.name.equals("spliterator")));
        for (String nativeDecision : List.of("showCosmeticAccessories", "shouldRender")) {
            assertEquals(1, Arrays.stream(method.instructions.toArray())
                    .filter(i -> i instanceof MethodInsnNode call && call.name.equals(nativeDecision)).count());
        }
    }

    @Test void emptyAndSingleSlotLookupsKeepTheNativeResultWithoutAnySplitting() throws Exception {
        Object cape = new Object();
        for (List<Object> entries : List.of(List.of(), List.of(cape))) {
            var nativeSource = new CountingSpliterator<>(entries);
            Object nativeResult = StreamSupport.stream(nativeSource, true).findFirst().orElse(null);
            var optimizedSource = new CountingSpliterator<>(entries);
            boolean parallel = useParallelLookup(true, entries.size(), 1);
            Object optimizedResult = StreamSupport.stream(optimizedSource, parallel).findFirst().orElse(null);
            assertSame(nativeResult, optimizedResult);
            assertEquals(0, optimizedSource.splits.get(), "Avoid the unknown-size iterator's batch array allocation");
            if (!entries.isEmpty()) {
                assertTrue(nativeSource.splits.get() > 0, "Reproduce the native parallel splitting path");
            }
        }
    }

    @Test void eitherExpandedInventoryRetainsNativeParallelSelectionAndSequentialInputsStaySequential() throws Exception {
        for (int actual : new int[]{0, 1, 2, 64}) {
            for (int cosmetic : new int[]{0, 1, 2, 64}) {
                boolean parallel = useParallelLookup(true, actual, cosmetic);
                assertEquals(actual > 1 || cosmetic > 1, parallel);
                assertFalse(useParallelLookup(false, actual, cosmetic));
            }
        }
    }

    private static boolean useParallelLookup(boolean parallel, int actual, int cosmetic) throws Exception {
        var method = AetherCapeLookupMixin.class.getDeclaredMethod("emfattachments$useParallelLookup",
                boolean.class, int.class, int.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, parallel, actual, cosmetic);
    }

    private static AbstractInsnNode previousInstruction(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static AbstractInsnNode nextInstruction(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static ClassNode classNode(String name) throws Exception {
        try (InputStream bytes = AetherCapeLookupTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(bytes);
            var node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static final class CountingSpliterator<T> implements Spliterator<T> {
        private final Spliterator<T> delegate;
        private final AtomicInteger splits;

        CountingSpliterator(List<T> entries) {
            this(Spliterators.spliteratorUnknownSize(entries.iterator(), 0), new AtomicInteger());
        }

        private CountingSpliterator(Spliterator<T> delegate, AtomicInteger splits) {
            this.delegate = delegate;
            this.splits = splits;
        }

        public boolean tryAdvance(Consumer<? super T> action) { return delegate.tryAdvance(action); }
        public long estimateSize() { return delegate.estimateSize(); }
        public int characteristics() { return delegate.characteristics(); }
        public Spliterator<T> trySplit() {
            splits.incrementAndGet();
            var split = delegate.trySplit();
            return split == null ? null : new CountingSpliterator<>(split, splits);
        }
    }
}
