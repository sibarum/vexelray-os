package dev.vexelray.compositor.spike;

import dev.supirvast.vastir.core.Buffer;
import dev.supirvast.vastir.core.Builtin;
import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.shader.ShaderSource;
import dev.supirvast.vastir.shader.Shaders;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.shader.ComposedShader;

import java.util.ArrayList;
import java.util.List;

import static dev.vexelray.ir.Ir.F32;
import static dev.vexelray.ir.Ir.I32;
import static dev.vexelray.ir.Ir.add;
import static dev.vexelray.ir.Ir.div;
import static dev.vexelray.ir.Ir.f;
import static dev.vexelray.ir.Ir.i;
import static dev.vexelray.ir.Ir.mix;
import static dev.vexelray.ir.Ir.mul;
import static dev.vexelray.ir.Ir.step;
import static dev.vexelray.ir.Ir.sub;
import static dev.vexelray.ir.Ir.toInt;
import static dev.vexelray.ir.Ir.v2;
import static dev.vexelray.ir.Ir.v4;

/**
 * The instanced vertex stage (gap G1 in miniature): draw six vertices per instance with no vertex buffer, read the
 * instance from a storage buffer by {@code VertexIndex / 6}, and rebuild the same varyings the canvas vertex shader
 * forwards. The fragment stage is the canvas's own, unchanged, so looks and MSDF text are exactly the canvas's.
 */
public final class InstanceShader {

    /** Sets 0 to 2 are the canvas fragment's (atlas, image, shadelet tables). */
    public static final int INSTANCES_SET = 3;
    public static final Buffer INSTANCES = new Buffer("instances", INSTANCES_SET, 0, F32);

    private InstanceShader() {
    }

    public static ComposedShader vertex() {
        return new ComposedShader(ShaderStage.VERTEX, Shaders.loadOrLower(new Source()), "main");
    }

    static final class Source implements ShaderSource {
        @Override
        public String name() {
            return "m0.instance.vert";
        }

        @Override
        public CoreModule module() {
            return module0();
        }
    }

    private static Expr load(Expr index) {
        return new Expr.BufferLoad(INSTANCES, index);
    }

    private static Expr read(LocalVar v) {
        return new Expr.Read(v);
    }

    private static CoreModule module0() {
        List<Statement> body = new ArrayList<>();
        LocalVar vi = new LocalVar("vi", I32);
        LocalVar inst = new LocalVar("inst", I32);
        LocalVar base = new LocalVar("base", I32);
        LocalVar k = new LocalVar("k", F32);
        LocalVar cx = new LocalVar("cx", F32);
        LocalVar cy = new LocalVar("cy", F32);
        LocalVar win = new LocalVar("win", I32);
        LocalVar px = new LocalVar("px", F32);
        LocalVar py = new LocalVar("py", F32);

        body.add(new Statement.DeclareVar(vi, new Expr.BuiltinRead(Builtin.VERTEX_INDEX)));
        body.add(new Statement.DeclareVar(inst, div(read(vi), i(6))));
        body.add(new Statement.DeclareVar(base,
                add(i(Wire.G_INSTANCES), mul(read(inst), i(Wire.INSTANCE_FLOATS)))));
        body.add(new Statement.DeclareVar(k, new Expr.Convert(sub(read(vi), mul(read(inst), i(6))), F32)));
        // Corners of the two triangles: 0 (0,0) 1 (1,0) 2 (0,1) 3 (1,0) 4 (1,1) 5 (0,1).
        Expr kk = read(k);
        body.add(new Statement.DeclareVar(cx, add(
                mul(step(f(0.5), kk), sub(f(1.0), step(f(1.5), kk))),
                mul(step(f(2.5), kk), sub(f(1.0), step(f(4.5), kk))))));
        body.add(new Statement.DeclareVar(cy, add(
                mul(step(f(1.5), kk), sub(f(1.0), step(f(2.5), kk))),
                step(f(3.5), kk))));
        Expr b = read(base);
        body.add(new Statement.DeclareVar(win, toInt(load(b))));
        body.add(new Statement.DeclareVar(px,
                mix(load(add(b, i(Wire.I_POS))), load(add(b, i(Wire.I_POS + 2))), read(cx))));
        body.add(new Statement.DeclareVar(py,
                mix(load(add(b, i(Wire.I_POS + 1))), load(add(b, i(Wire.I_POS + 3))), read(cy))));

        Expr origin = add(i(Wire.G_WINDOWS), mul(read(win), i(4)));
        Expr sx = add(read(px), load(origin));
        Expr sy = add(read(py), load(add(origin, i(1))));
        Expr clipX = sub(mul(div(sx, load(i(Wire.G_SCREEN))), f(2.0)), f(1.0));
        Expr clipY = sub(mul(div(sy, load(i(Wire.G_SCREEN + 1))), f(2.0)), f(1.0));
        body.add(new Statement.BuiltinWrite(Builtin.POSITION, v4(clipX, clipY, f(0.0), f(1.0))));

        CanvasShader.Varyings out = CanvasShader.Varyings.outputs();
        body.add(new Statement.InterfaceWrite(out.color(), vec4(b, Wire.I_COLOR)));
        body.add(new Statement.InterfaceWrite(out.uv(), v2(
                mix(load(add(b, i(Wire.I_UV))), load(add(b, i(Wire.I_UV + 2))), read(cx)),
                mix(load(add(b, i(Wire.I_UV + 1))), load(add(b, i(Wire.I_UV + 3))), read(cy)))));
        body.add(new Statement.InterfaceWrite(out.local(), v2(
                mix(load(add(b, i(Wire.I_LOCAL))), load(add(b, i(Wire.I_LOCAL + 2))), read(cx)),
                mix(load(add(b, i(Wire.I_LOCAL + 1))), load(add(b, i(Wire.I_LOCAL + 3))), read(cy)))));
        body.add(new Statement.InterfaceWrite(out.shape(), vec4(b, Wire.I_SHAPE)));
        body.add(new Statement.InterfaceWrite(out.clipBox(), vec4(b, Wire.I_CLIP_BOX)));
        // The fragment's own position is in the window's px, the same space as its clip box.
        body.add(new Statement.InterfaceWrite(out.clipRs(), v4(read(px), read(py),
                load(add(b, i(Wire.I_CLIP_R))), load(add(b, i(Wire.I_CLIP_AA))))));
        body.add(new Statement.InterfaceWrite(out.filter(), vec4(b, Wire.I_FILTER)));
        body.add(new Statement.InterfaceWrite(out.element(), toInt(load(add(b, i(Wire.I_ELEMENT))))));
        body.add(new Statement.ReturnVoid());

        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), new Region(body));
        return new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.VERTEX));
    }

    private static Expr vec4(Expr base, int at) {
        return v4(load(add(base, i(at))), load(add(base, i(at + 1))), load(add(base, i(at + 2))),
                load(add(base, i(at + 3))));
    }
}
