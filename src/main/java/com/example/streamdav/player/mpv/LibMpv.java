package com.example.streamdav.player.mpv;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Bindings for the parts of libmpv's client and render APIs that StreamDav uses ({@code mpv/client.h} and
 * {@code mpv/render.h}, client API 2.x), via the Foreign Function &amp; Memory API.
 */
public final class LibMpv {
    private static final Logger log = LogManager.getLogger(LibMpv.class);

    static final int FORMAT_NONE = 0;
    static final int FORMAT_STRING = 1;
    static final int FORMAT_FLAG = 3;
    static final int FORMAT_INT64 = 4;
    static final int FORMAT_DOUBLE = 5;

    static final int EVENT_NONE = 0;
    static final int EVENT_SHUTDOWN = 1;
    static final int EVENT_LOG_MESSAGE = 2;
    static final int EVENT_END_FILE = 7;
    static final int EVENT_FILE_LOADED = 8;
    static final int EVENT_PLAYBACK_RESTART = 21;
    static final int EVENT_PROPERTY_CHANGE = 22;

    static final int END_FILE_REASON_ERROR = 4;

    static final long RENDER_UPDATE_FRAME = 1;

    private static final int RENDER_PARAM_INVALID = 0;
    private static final int RENDER_PARAM_API_TYPE = 1;
    private static final int RENDER_PARAM_SW_SIZE = 17;
    private static final int RENDER_PARAM_SW_FORMAT = 18;
    private static final int RENDER_PARAM_SW_STRIDE = 19;
    private static final int RENDER_PARAM_SW_POINTER = 20;

    /** {@code struct mpv_render_param { enum mpv_render_param_type type; void *data; }} */
    private static final StructLayout RENDER_PARAM =
            MemoryLayout.structLayout(JAVA_INT.withName("type"), MemoryLayout.paddingLayout(4), ADDRESS.withName("data"));
    private static final long EVENT_SIZE = 24;

    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle create;
    private final MethodHandle initialize;
    private final MethodHandle terminateDestroy;
    private final MethodHandle setOptionString;
    private final MethodHandle setPropertyString;
    private final MethodHandle getPropertyString;
    private final MethodHandle free;
    private final MethodHandle command;
    private final MethodHandle observeProperty;
    private final MethodHandle requestLogMessages;
    private final MethodHandle waitEvent;
    private final MethodHandle wakeup;
    private final MethodHandle errorString;
    private final MethodHandle renderContextCreate;
    private final MethodHandle renderContextSetUpdateCallback;
    private final MethodHandle renderContextUpdate;
    private final MethodHandle renderContextRender;
    private final MethodHandle renderContextFree;

    private LibMpv(SymbolLookup lookup) {
        create = bind(lookup, "mpv_create", FunctionDescriptor.of(ADDRESS));
        initialize = bind(lookup, "mpv_initialize", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        terminateDestroy = bind(lookup, "mpv_terminate_destroy", FunctionDescriptor.ofVoid(ADDRESS));
        setOptionString = bind(lookup, "mpv_set_option_string", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        setPropertyString = bind(lookup, "mpv_set_property_string", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        getPropertyString = bind(lookup, "mpv_get_property_string", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        free = bind(lookup, "mpv_free", FunctionDescriptor.ofVoid(ADDRESS));
        command = bind(lookup, "mpv_command", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        observeProperty = bind(lookup, "mpv_observe_property",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT));
        requestLogMessages = bind(lookup, "mpv_request_log_messages", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        waitEvent = bind(lookup, "mpv_wait_event", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE));
        wakeup = bind(lookup, "mpv_wakeup", FunctionDescriptor.ofVoid(ADDRESS));
        errorString = bind(lookup, "mpv_error_string", FunctionDescriptor.of(ADDRESS, JAVA_INT));
        renderContextCreate = bind(lookup, "mpv_render_context_create",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        renderContextSetUpdateCallback = bind(lookup, "mpv_render_context_set_update_callback",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        renderContextUpdate = bind(lookup, "mpv_render_context_update", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        renderContextRender = bind(lookup, "mpv_render_context_render", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        renderContextFree = bind(lookup, "mpv_render_context_free", FunctionDescriptor.ofVoid(ADDRESS));
    }

    /** Loads libmpv if it's installed and new enough (client API 2.x); otherwise empty. */
    public static Optional<LibMpv> load() {
        for (String name : candidates()) {
            try {
                SymbolLookup lookup = name.contains("/") || name.contains("\\")
                        ? SymbolLookup.libraryLookup(Path.of(name), Arena.global())
                        : SymbolLookup.libraryLookup(name, Arena.global());
                MethodHandle version = LINKER.downcallHandle(lookup.find("mpv_client_api_version").orElseThrow(),
                        FunctionDescriptor.of(LINKER.canonicalLayouts().get("long")));
                long apiVersion = ((Number) version.invoke()).longValue();
                if (apiVersion >> 16 != 2) {
                    log.info("Ignoring {}: client API {}.{}, need 2.x", name, apiVersion >> 16, apiVersion & 0xFFFF);
                    continue;
                }
                log.info("Using {} (client API 2.{})", name, apiVersion & 0xFFFF);
                return Optional.of(new LibMpv(lookup));
            } catch (Throwable e) {
                log.debug("libmpv not loaded from {}: {}", name, e.toString());
            }
        }
        log.info("libmpv not found; using JavaFX Media and external players");
        return Optional.empty();
    }

    private static List<String> candidates() {
        String override = System.getProperty("streamdav.libmpv");
        if (override != null && !override.isBlank()) {
            return List.of(override);
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("libmpv-2.dll", "mpv-2.dll");
        }
        if (os.contains("mac")) {
            return List.of("libmpv.2.dylib", "/opt/homebrew/lib/libmpv.2.dylib", "/usr/local/lib/libmpv.2.dylib");
        }
        return List.of("libmpv.so.2", "libmpv.so");
    }

    private static MethodHandle bind(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.find(name).orElseThrow(), descriptor);
    }

    // Client API

    /** Creates an uninitialised mpv core, or returns {@link MemorySegment#NULL} on failure. */
    MemorySegment create() {
        useCNumericLocale();
        try {
            return (MemorySegment) create.invokeExact();
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    int initialize(MemorySegment handle) {
        try {
            return (int) initialize.invokeExact(handle);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    void terminateDestroy(MemorySegment handle) {
        try {
            terminateDestroy.invokeExact(handle);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    int setOption(MemorySegment handle, String name, String value) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) setOptionString.invokeExact(handle, arena.allocateFrom(name), arena.allocateFrom(value));
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    int setProperty(MemorySegment handle, String name, String value) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) setPropertyString.invokeExact(handle, arena.allocateFrom(name), arena.allocateFrom(value));
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    /** The property formatted as a string, or null if it's unavailable. */
    String getProperty(MemorySegment handle, String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment value = (MemorySegment) getPropertyString.invokeExact(handle, arena.allocateFrom(name));
            if (value.equals(MemorySegment.NULL)) {
                return null;
            }
            String result = string(value);
            free.invokeExact(value);
            return result;
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    int command(MemorySegment handle, String... args) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment argv = arena.allocate(ADDRESS, args.length + 1);
            for (int i = 0; i < args.length; i++) {
                argv.setAtIndex(ADDRESS, i, arena.allocateFrom(args[i]));
            }
            argv.setAtIndex(ADDRESS, args.length, MemorySegment.NULL);
            return (int) command.invokeExact(handle, argv);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    int observeProperty(MemorySegment handle, String name, int format) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) observeProperty.invokeExact(handle, 0L, arena.allocateFrom(name), format);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    int requestLogMessages(MemorySegment handle, String minLevel) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) requestLogMessages.invokeExact(handle, arena.allocateFrom(minLevel));
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    /** Waits up to {@code timeout} seconds (negative: forever) for the next event. */
    Event waitEvent(MemorySegment handle, double timeout) {
        try {
            MemorySegment event = ((MemorySegment) waitEvent.invokeExact(handle, timeout)).reinterpret(EVENT_SIZE);
            // struct mpv_event { mpv_event_id event_id; int error; uint64_t reply_userdata; void *data; }
            return new Event(event.get(JAVA_INT, 0), event.get(JAVA_INT, 4), event.get(ADDRESS, 16));
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    /** Makes a pending {@link #waitEvent} return, with an {@link #EVENT_NONE} event. */
    void wakeup(MemorySegment handle) {
        try {
            wakeup.invokeExact(handle);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    String errorString(int error) {
        try {
            return string((MemorySegment) errorString.invokeExact(error));
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    // Event payloads

    record Event(int id, int error, MemorySegment data) {
    }

    /** A changed property; {@code value} is a String, Boolean, Long or Double, or null when unavailable. */
    record Property(String name, Object value) {
    }

    static Property property(Event event) {
        // struct mpv_event_property { const char *name; mpv_format format; void *data; }
        MemorySegment property = event.data().reinterpret(24);
        String name = string(property.get(ADDRESS, 0));
        MemorySegment data = property.get(ADDRESS, 16);
        Object value = switch (property.get(JAVA_INT, 8)) {
            case FORMAT_STRING -> string(data.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0));
            case FORMAT_FLAG -> data.reinterpret(4).get(JAVA_INT, 0) != 0;
            case FORMAT_INT64 -> data.reinterpret(8).get(JAVA_LONG, 0);
            case FORMAT_DOUBLE -> data.reinterpret(8).get(JAVA_DOUBLE, 0);
            default -> null;
        };
        return new Property(name, value);
    }

    /** {@code struct mpv_event_end_file { mpv_end_file_reason reason; int error; ... }} */
    static int endFileReason(Event event) {
        return event.data().reinterpret(8).get(JAVA_INT, 0);
    }

    static int endFileError(Event event) {
        return event.data().reinterpret(8).get(JAVA_INT, 4);
    }

    /** {@code struct mpv_event_log_message { const char *prefix; const char *level; const char *text; ... }} */
    static String logMessage(Event event) {
        MemorySegment message = event.data().reinterpret(24);
        return "[" + string(message.get(ADDRESS, 0)) + "] " + string(message.get(ADDRESS, 16)).strip();
    }

    // Render API, software renderer

    MemorySegment createSoftwareRenderContext(MemorySegment handle) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment params = arena.allocate(RENDER_PARAM, 2);
            setParam(params, 0, RENDER_PARAM_API_TYPE, arena.allocateFrom("sw"));
            setParam(params, 1, RENDER_PARAM_INVALID, MemorySegment.NULL);
            MemorySegment result = arena.allocate(ADDRESS);
            int error = (int) renderContextCreate.invokeExact(result, handle, params);
            if (error < 0) {
                throw new IllegalStateException("mpv_render_context_create failed: " + errorString(error));
            }
            return result.get(ADDRESS, 0);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    /**
     * Calls {@code callback} on an mpv thread whenever a new frame should be rendered. It must not call back into
     * mpv, and must not throw. The stub lives as long as {@code arena}.
     */
    void setUpdateCallback(MemorySegment renderContext, Runnable callback, Arena arena) {
        try {
            MethodHandle run = MethodHandles.lookup()
                    .findVirtual(Runnable.class, "run", MethodType.methodType(void.class))
                    .bindTo(callback);
            MemorySegment stub = LINKER.upcallStub(MethodHandles.dropArguments(run, 0, MemorySegment.class),
                    FunctionDescriptor.ofVoid(ADDRESS), arena);
            renderContextSetUpdateCallback.invokeExact(renderContext, stub, MemorySegment.NULL);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    /** Returns {@link #RENDER_UPDATE_FRAME} set when a new frame is ready to render. */
    long renderContextUpdate(MemorySegment renderContext) {
        try {
            return (long) renderContextUpdate.invokeExact(renderContext);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    /**
     * Builds the parameters for rendering into {@code pixels} as 4-byte "bgr0" pixels. The fourth byte of each
     * pixel is left undefined by mpv. Allocated in {@code arena}, so they can be reused for every frame.
     */
    static MemorySegment softwareTarget(Arena arena, int width, int height, long stride, MemorySegment pixels) {
        MemorySegment size = arena.allocate(JAVA_INT, 2);
        size.setAtIndex(JAVA_INT, 0, width);
        size.setAtIndex(JAVA_INT, 1, height);
        MemorySegment strideValue = arena.allocate(JAVA_LONG);
        strideValue.set(JAVA_LONG, 0, stride);
        MemorySegment params = arena.allocate(RENDER_PARAM, 5);
        setParam(params, 0, RENDER_PARAM_SW_SIZE, size);
        setParam(params, 1, RENDER_PARAM_SW_FORMAT, arena.allocateFrom("bgr0"));
        setParam(params, 2, RENDER_PARAM_SW_STRIDE, strideValue);
        setParam(params, 3, RENDER_PARAM_SW_POINTER, pixels);
        setParam(params, 4, RENDER_PARAM_INVALID, MemorySegment.NULL);
        return params;
    }

    int render(MemorySegment renderContext, MemorySegment target) {
        try {
            return (int) renderContextRender.invokeExact(renderContext, target);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    void freeRenderContext(MemorySegment renderContext) {
        try {
            renderContextFree.invokeExact(renderContext);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    // Helpers

    private static void setParam(MemorySegment params, int index, int type, MemorySegment data) {
        MemorySegment param = params.asSlice(index * RENDER_PARAM.byteSize(), RENDER_PARAM);
        param.set(JAVA_INT, 0, type);
        param.set(ADDRESS, 8, data);
    }

    private static String string(MemorySegment pointer) {
        return pointer.equals(MemorySegment.NULL) ? null : pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /**
     * libmpv refuses to start unless LC_NUMERIC is "C", and the JVM inherits the user's locale. Java's own number
     * formatting doesn't use the C locale, so changing it is harmless.
     */
    private static void useCNumericLocale() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return;
        }
        int lcNumeric = os.contains("linux") ? 1 : 4; // glibc vs. BSD/macOS values of LC_NUMERIC
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle setlocale = LINKER.downcallHandle(LINKER.defaultLookup().find("setlocale").orElseThrow(),
                    FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS));
            MemorySegment _ = (MemorySegment) setlocale.invokeExact(lcNumeric, arena.allocateFrom("C"));
        } catch (Throwable e) {
            log.warn("Could not set LC_NUMERIC to C; libmpv may refuse to start", e);
        }
    }

    private static RuntimeException rethrow(Throwable e) {
        if (e instanceof RuntimeException runtime) {
            return runtime;
        }
        if (e instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(e);
    }
}
