import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.util.*;

/**
 * Prints the return values of every method of the given classes as they return, from a JVM started with a JDWP
 * agent (see Trace.java): {@code java scripts/jdi/Ret.java PORT owner.Class[,other.Class] SKIP LIMIT}. Sort and count
 * the output to see which checks keep answering what (how ComposeIdlingResource's busy layout check was found).
 * Method-exit events slow the JVM a lot: keep SKIP small and the classes few.
 */
public class Ret {
  public static void main(String[] a) throws Exception {
    AttachingConnector c = Bootstrap.virtualMachineManager().attachingConnectors().stream()
        .filter(x -> x.name().equals("com.sun.jdi.SocketAttach")).findFirst().get();
    Map<String, Connector.Argument> args = c.defaultArguments();
    args.get("port").setValue(a[0]);
    VirtualMachine vm = c.attach(args);
    MethodExitRequest r = vm.eventRequestManager().createMethodExitRequest();
    for (String cls : a[1].split(",")) r.addClassFilter(cls);
    r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
    r.enable();
    long skip = Long.parseLong(a[2]), limit = Long.parseLong(a[3]), n = 0;
    vm.resume();
    while (true) {
      EventSet set = vm.eventQueue().remove();
      for (Event e : set) {
        if (e instanceof MethodExitEvent x) {
          n++;
          if (n > skip) System.out.println(x.method().declaringType().name() + "." + x.method().name() + " -> " + x.returnValue());
          if (n > skip + limit) { vm.dispose(); return; }
        }
        if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) return;
      }
      set.resume();
    }
  }
}
