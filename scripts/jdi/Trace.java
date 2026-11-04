import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.util.*;

/**
 * Prints each call of the named methods with its arguments and a short stack, from a JVM started with
 * {@code --jvm "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=PORT"}.
 *
 * <pre>java scripts/jdi/Trace.java PORT owner.Class[,other.Class] method [method...]</pre>
 *
 * -Dskip=N ignores the first N hits, -Dlimit=N stops after N more, -Ddepth=N frames per hit (14), and
 * -DthisView=true also prints the receiver's getView() (a ViewRootImpl's window). Translated app classes have line
 * numbers only with --debug, so frames show methods and offsets.
 */
public class Trace {
  static long hits;
  static final long SKIP = Long.getLong("skip", 0);
  static final long LIMIT = Long.getLong("limit", 1000000);
  public static void main(String[] a) throws Exception {
    AttachingConnector c = Bootstrap.virtualMachineManager().attachingConnectors().stream()
        .filter(x -> x.name().equals("com.sun.jdi.SocketAttach")).findFirst().get();
    Map<String, Connector.Argument> args = c.defaultArguments();
    args.get("port").setValue(a[0]);
    args.get("timeout").setValue("60000");
    VirtualMachine vm = c.attach(args);
    List<String> classes = Arrays.asList(a[1].split(","));
    Set<String> methods = new HashSet<>(Arrays.asList(a).subList(2, a.length));
    EventRequestManager m = vm.eventRequestManager();
    for (String cls : classes) {
      ClassPrepareRequest r = m.createClassPrepareRequest();
      r.addClassFilter(cls);
      r.setSuspendPolicy(EventRequest.SUSPEND_ALL);
      r.enable();
      for (ReferenceType t : vm.classesByName(cls)) arm(m, t, methods);
    }
    vm.resume();
    while (true) {
      EventSet set = vm.eventQueue().remove();
      for (Event e : set) {
        if (e instanceof ClassPrepareEvent p) arm(m, p.referenceType(), methods);
        if (e instanceof BreakpointEvent b && ++hits <= SKIP) continue;
        if (e instanceof BreakpointEvent b && hits > SKIP + LIMIT) { vm.dispose(); return; }
        if (e instanceof BreakpointEvent b) {
          ThreadReference th = b.thread();
          StringBuilder s = new StringBuilder("HIT " + b.location().method().name() + " on " + th.name());
          try {
            if (!b.location().method().argumentTypeNames().isEmpty()) {
              StackFrame f = th.frame(0);
              s.append(" args=").append(f.getArgumentValues());
              for (Value v : f.getArgumentValues()) {
                if (v instanceof ObjectReference o) {
                  Method ts = o.referenceType().methodsByName("toString", "()Ljava/lang/String;").get(0);
                  s.append("\n    = ").append(o.invokeMethod(th, ts, List.of(), ObjectReference.INVOKE_SINGLE_THREADED));
                }
              }
            }
            ObjectReference self = th.frame(0).thisObject();
            if (self != null && Boolean.getBoolean("thisView")) {
              Method gv = self.referenceType().methodsByName("getView").stream().findFirst().orElse(null);
              if (gv != null) {
                Value v = self.invokeMethod(th, gv, List.of(), ObjectReference.INVOKE_SINGLE_THREADED);
                s.append(" view=").append(v == null ? "null" : ((ObjectReference) v).referenceType().name() + "@" + ((ObjectReference) v).uniqueID());
              }
            }
            List<StackFrame> fr = th.frames();
            for (int i = 1; i < Math.min(fr.size(), Integer.getInteger("depth", 14)); i++) s.append("\n    ").append(fr.get(i).location());
          } catch (Exception x) { s.append(" ").append(x); }
          System.out.println(s);
        }
        if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) return;
      }
      set.resume();
    }
  }

  static void arm(EventRequestManager m, ReferenceType t, Set<String> methods) {
    for (Method me : t.methods()) {
      if (methods.contains(me.name()) && me.location() != null) {
        BreakpointRequest r = m.createBreakpointRequest(me.location());
        r.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        r.enable();
      }
    }
  }
}
