package minipg;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Implements a java.sql interface by forwarding each call to a same-named public method of an implementation object. */
final class Dispatch implements InvocationHandler {
    private static final Map<String, Method> CACHE = new ConcurrentHashMap<>();
    private static final Method MISSING;
    static { try { MISSING = Object.class.getMethod("toString"); } catch (NoSuchMethodException e) { throw new ExceptionInInitializerError(e); } }
    private final Object impl;
    private final Class<?> iface;

    private Dispatch(Object impl, Class<?> iface) { this.impl = impl; this.iface = iface; }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> iface, Object impl) {
        return (T) Proxy.newProxyInstance(Dispatch.class.getClassLoader(), new Class<?>[]{iface}, new Dispatch(impl, iface));
    }

    @Override public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
        String n = m.getName();
        switch (n) {
            case "unwrap": {
                Class<?> want = (Class<?>) args[0];
                if (want.isInstance(impl)) return impl;
                if (want.isInstance(proxy)) return proxy;
                throw new java.sql.SQLException("not a wrapper for " + want);
            }
            case "isWrapperFor": { Class<?> want = (Class<?>) args[0]; return want.isInstance(impl) || want.isInstance(proxy); }
            case "hashCode": if (args == null) return System.identityHashCode(proxy); break;
            case "equals": if (args != null && args.length == 1 && m.getParameterTypes()[0] == Object.class) return proxy == args[0]; break;
            case "toString": if (args == null) return impl.toString(); break;
            default: break;
        }
        String key = impl.getClass().getName() + "#" + n + java.util.Arrays.toString(m.getParameterTypes());
        Method target = CACHE.computeIfAbsent(key, k -> {
            try { return impl.getClass().getMethod(n, m.getParameterTypes()); }
            catch (NoSuchMethodException e) { return MISSING; }
        });
        if (target == MISSING)
            throw new SQLFeatureNotSupportedException("minipg does not implement " + iface.getSimpleName() + "." + n);
        try { return target.invoke(impl, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
}
