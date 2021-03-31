package net.sorenon.minexraft;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.sorenon.minexraft.mixin.accessor.EntityExt;
import net.sorenon.minexraft.accessor.FBAccessor;
import net.sorenon.minexraft.accessor.MinecraftClientEXT;
import org.joml.*;
import org.joml.Math;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.openxr.*;
import org.lwjgl.system.*;
import org.lwjgl.system.dyncall.DynCall;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;

import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryStack.stackMallocLong;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Slightly tweaked combination of
 * https://github.com/maluoi/OpenXRSamples/blob/master/SingleFileExample
 * and
 * https://github.com/ReliaSolve/OpenXR-OpenGL-Example
 * Can only run on windows until glfw is updated
 * Requires a stereo headset and an install of the OpenXR runtime to run
 */
public class OpenXR {

    //XR globals
    //Init
    public XrInstance xrInstance;
    public long systemID;
    public Struct graphicsBinding;
    public XrSession xrSession;
    XrSpace xrAppSpace;  //The base world space in which the program runs
    XrSpace xrViewSpace;
    long glColorFormat;
    XrView.Buffer views;       //Each view represents an eye in the headset with views[0] being left and views[1] being right
    public Swapchain[] swapchains;  //One swapchain per view
    XrViewConfigurationView.Buffer viewConfigs;
    int viewConfigType = XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    public InputState inputState;

    //Runtime
    public XrEventDataBuffer eventDataBuffer;
    int sessionState;
    public boolean sessionRunning;

    MinecraftClient client = MinecraftClient.getInstance();
    MinecraftClientEXT clientExt = ((MinecraftClientEXT) MinecraftClient.getInstance());

    public static final XrPosef identityPose = XrPosef.malloc().set(
            XrQuaternionf.mallocStack().set(0, 0, 0, 1),
            XrVector3f.callocStack()
    );

    public static class Swapchain {
        XrSwapchain handle;
        public int width;
        public int height;
        XrSwapchainImageOpenGLKHR.Buffer images;
    }

    public static class InputState implements NativeResource {
        XrActionSet actionSet;
        XrAction poseAction;
        XrAction triggerAction;
        XrAction thumbstickAction;
        XrAction aPressAction;
        LongBuffer handSubactionPath = memAllocLong(2);
        XrSpace[] handSpace = new XrSpace[2];
        boolean[] renderHand = new boolean[2];
        boolean[] trigger = new boolean[2];
        public Pose[] poses = {new Pose(), new Pose()};
        public XrVector2f[] handThumbstick = {XrVector2f.calloc(), XrVector2f.calloc()};
        public boolean[] aDown = {false, false};

        @Override
        public void free() {
            memFree(handSubactionPath);
        }
    }

    public static void main(String[] args) throws InterruptedException {


        if (true) return;
//            XR.create("C:\\Program Files (x86)\\Steam\\steamapps\\common\\SteamVR\\bin\\win64\\openxr_loader.dll");
//        XR.create();

        OpenXR openXR = new OpenXR();
//        helloOpenXR.createOpenXRInstance();
//        helloOpenXR.initializeOpenXRSystem();
//        helloOpenXR.initializeAndBindOpenGL();
//        helloOpenXR.createXRReferenceSpace();
//        helloOpenXR.createXRSwapchains();
//        helloOpenXR.createOpenGLResourses();

        openXR.eventDataBuffer = XrEventDataBuffer.calloc();
        openXR.eventDataBuffer.type(XR10.XR_TYPE_EVENT_DATA_BUFFER);
//        while (!helloOpenXR.pollEvents() && !glfwWindowShouldClose(helloOpenXR.window)) {
//            if (helloOpenXR.sessionRunning) {
//                helloOpenXR.renderFrameOpenXR();
//            } else {
//                // Throttle loop since xrWaitFrame won't be called.
//                Thread.sleep(250);
//            }
//        }

        //Destroy OpenXR
        openXR.eventDataBuffer.free();
        openXR.graphicsBinding.free();
        openXR.views.free();
        openXR.viewConfigs.free();
        for (Swapchain swapchain : openXR.swapchains) {
            swapchain.images.free();
        }
    }

    /**
     * Creates an array of XrStructs with their types pre set to @param type
     */
    private static ByteBuffer mallocAndFillBufferStack(int capacity, int sizeof, int type) {
        ByteBuffer b = stackMalloc(capacity * sizeof);

        for (int i = 0; i < capacity; i++) {
            b.position(i * sizeof);
            b.putInt(type);
        }
        b.rewind();
        return b;
    }

    private static ByteBuffer mallocAndFillBufferHeap(int capacity, int sizeof, int type) {
        ByteBuffer b = memAlloc(capacity * sizeof);

        for (int i = 0; i < capacity; i++) {
            b.position(i * sizeof);
            b.putInt(type);
        }
        b.rewind();
        return b;
    }

    public void createOpenXRInstance() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer numExtensions = stack.mallocInt(1);
            xrCheck(XR10.xrEnumerateInstanceExtensionProperties((ByteBuffer) null, numExtensions, null));

            XrExtensionProperties.Buffer properties = new XrExtensionProperties.Buffer(
                    mallocAndFillBufferStack(numExtensions.get(0), XrExtensionProperties.SIZEOF, XR10.XR_TYPE_EXTENSION_PROPERTIES)
            );

            xrCheck(XR10.xrEnumerateInstanceExtensionProperties((ByteBuffer) null, numExtensions, properties));

            System.out.printf("OpenXR loaded with %d extensions:%n", numExtensions.get(0));
            System.out.println("~~~~~~~~~~~~~~~~~~");
            PointerBuffer extensions = stack.mallocPointer(numExtensions.get(0));
            boolean missingOpenGL = true;
            while (properties.hasRemaining()) {
                XrExtensionProperties prop = properties.get();
                String extensionName = prop.extensionNameString();
                System.out.println(extensionName);
                extensions.put(memASCII(extensionName));
                if (extensionName.equals(KHROpenglEnable.XR_KHR_OPENGL_ENABLE_EXTENSION_NAME)) {
                    missingOpenGL = false;
                }
            }
            extensions.rewind();
            System.out.println("~~~~~~~~~~~~~~~~~~");

            if (missingOpenGL) {
                throw new IllegalStateException("OpenXR library does not provide required extension: " + KHROpenglEnable.XR_KHR_OPENGL_ENABLE_EXTENSION_NAME);
            }

            XrApplicationInfo applicationInfo = XrApplicationInfo.mallocStack();
            applicationInfo.apiVersion(XR10.XR_CURRENT_API_VERSION);
            applicationInfo.applicationName(stack.UTF8("HelloOpenXR"));

            XrInstanceCreateInfo createInfo = XrInstanceCreateInfo.mallocStack();
            createInfo.set(
                    XR10.XR_TYPE_INSTANCE_CREATE_INFO,
                    0,
                    0,
                    applicationInfo,
                    null,
                    extensions
            );

            PointerBuffer instancePtr = stack.mallocPointer(1);
            xrCheck(XR10.xrCreateInstance(createInfo, instancePtr));
            xrInstance = new XrInstance(instancePtr.get(0), createInfo);
        }
    }

    public void initializeOpenXRSystem() {
        try (MemoryStack stack = stackPush()) {
            //Get headset
            XrSystemGetInfo systemInfo = XrSystemGetInfo.mallocStack();
            systemInfo.set(XR10.XR_TYPE_SYSTEM_GET_INFO, 0, XR10.XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY);

            LongBuffer lBuf = stack.longs(0);
            xrCheck(XR10.xrGetSystem(xrInstance, systemInfo, lBuf));
            systemID = lBuf.get();
            if (systemID == 0) {
                throw new IllegalStateException("No compatible headset detected");
            }
            System.out.printf("Headset found with System ID:%d\n", systemID);
        }
    }

    public void createXRReferenceSpaces() {
        try (MemoryStack stack = stackPush()) {
            XrPosef identityPose = XrPosef.mallocStack();
            identityPose.set(
                    XrQuaternionf.mallocStack().set(0, 0, 0, 1),
                    XrVector3f.callocStack()
            );

            XrReferenceSpaceCreateInfo referenceSpaceCreateInfo = XrReferenceSpaceCreateInfo.mallocStack();
            referenceSpaceCreateInfo.set(
                    XR10.XR_TYPE_REFERENCE_SPACE_CREATE_INFO,
                    NULL,
                    XR10.XR_REFERENCE_SPACE_TYPE_STAGE, //TODO make this local
                    identityPose
            );
            PointerBuffer pp = stack.mallocPointer(1);
            xrCheck(XR10.xrCreateReferenceSpace(xrSession, referenceSpaceCreateInfo, pp));
            xrAppSpace = new XrSpace(pp.get(0), xrSession);

            referenceSpaceCreateInfo.referenceSpaceType(XR10.XR_REFERENCE_SPACE_TYPE_VIEW);
            xrCheck(XR10.xrCreateReferenceSpace(xrSession, referenceSpaceCreateInfo, pp));
            xrViewSpace = new XrSpace(pp.get(0), xrSession);
        }
    }

    public void createXRSwapchains() {
        try (MemoryStack stack = stackPush()) {
//            ByteBuffer buf = stack.calloc(XrSystemProperties.SIZEOF);
//            buf.putInt(XR10.XR_TYPE_SYSTEM_PROPERTIES);
//            buf.rewind();
            XrSystemProperties systemProperties = XrSystemProperties.callocStack();
            xrCheck(XR10.xrGetSystemProperties(xrInstance, systemID, systemProperties));

            System.out.printf("Headset name:%s vendor:%d \n", memUTF8(systemProperties.systemName()), systemProperties.vendorId());
            XrSystemTrackingProperties trackingProperties = systemProperties.trackingProperties();
            System.out.printf("Headset orientationTracking:%b positionTracking:%b \n", trackingProperties.orientationTracking(), trackingProperties.positionTracking());
            XrSystemGraphicsProperties graphicsProperties = systemProperties.graphicsProperties();
            System.out.printf("Headset MaxWidth:%d MaxHeight:%d MaxLayerCount:%d \n", graphicsProperties.maxSwapchainImageWidth(), graphicsProperties.maxSwapchainImageHeight(), graphicsProperties.maxLayerCount());

            IntBuffer intBuf = stack.mallocInt(1);
            xrCheck(XR10.xrEnumerateViewConfigurationViews(xrInstance, systemID, viewConfigType, intBuf, null));
            viewConfigs = new XrViewConfigurationView.Buffer(
                    mallocAndFillBufferHeap(intBuf.get(0), XrViewConfigurationView.SIZEOF, XR10.XR_TYPE_VIEW_CONFIGURATION_VIEW)
            );
            xrCheck(XR10.xrEnumerateViewConfigurationViews(xrInstance, systemID, viewConfigType, intBuf, viewConfigs));
            int viewCountNumber = intBuf.get(0);

            views = new XrView.Buffer(
                    mallocAndFillBufferHeap(viewCountNumber, XrView.SIZEOF, XR10.XR_TYPE_VIEW)
            );

            if (viewCountNumber > 0) {
                xrCheck(XR10.xrEnumerateSwapchainFormats(xrSession, intBuf, null));
                LongBuffer swapchainFormats = stack.mallocLong(intBuf.get(0));
                xrCheck(XR10.xrEnumerateSwapchainFormats(xrSession, intBuf, swapchainFormats));

                long[] desiredSwapchainFormats = {
                        GL_SRGB8_ALPHA8,
                        //SRGB formats perform automatic inverse gamma correction inside the driver
                        //but all SRGB formats are linear so in the future we want to do inverse gamma correction ourselves
                        //and use GL_RGBA16F instead
                        GL11.GL_RGB10_A2,
                        GL30.GL_RGBA16F,
                        // The two below should only be used as a fallback, as they are linear color formats without enough bits for color
                        // depth, thus leading to banding.
                        GL11.GL_RGBA8,
                        GL31.GL_RGBA8_SNORM
                };
                for (long glFormatIter : desiredSwapchainFormats) {
                    swapchainFormats.rewind();
                    while (swapchainFormats.hasRemaining()) {
                        if (glFormatIter == swapchainFormats.get()) {
                            glColorFormat = glFormatIter;
                            break;
                        }
                    }
                    if (glColorFormat != 0) {
                        break;
                    }
                }
                if (glColorFormat == 0) {
                    throw new IllegalStateException("No compatable swapchain / framebuffer format availible");
                }

                swapchains = new Swapchain[viewCountNumber];
                for (int i = 0; i < viewCountNumber; i++) {
                    XrViewConfigurationView viewConfig = viewConfigs.get(i);
                    XrSwapchainCreateInfo swapchainCreateInfo = XrSwapchainCreateInfo.mallocStack();
                    Swapchain swapchainWrapper = new Swapchain();

                    swapchainCreateInfo.set(
                            XR10.XR_TYPE_SWAPCHAIN_CREATE_INFO,
                            NULL,
                            0,
                            XR10.XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR10.XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT,
                            glColorFormat,
                            viewConfig.recommendedSwapchainSampleCount(),
                            viewConfig.recommendedImageRectWidth(),
                            viewConfig.recommendedImageRectHeight(),
                            1,
                            1,
                            1
                    );

                    PointerBuffer pp = stack.mallocPointer(1);
                    xrCheck(XR10.xrCreateSwapchain(xrSession, swapchainCreateInfo, pp));
                    swapchainWrapper.handle = new XrSwapchain(pp.get(0), xrSession);
                    swapchainWrapper.width = swapchainCreateInfo.width();
                    swapchainWrapper.height = swapchainCreateInfo.height();

                    xrCheck(XR10.xrEnumerateSwapchainImages(swapchainWrapper.handle, intBuf, null));
                    int imageCount = intBuf.get(0);
                    XrSwapchainImageOpenGLKHR.Buffer swapchainImageBuffer = XrSwapchainImageOpenGLKHR.create(imageCount);
                    for (XrSwapchainImageOpenGLKHR image : swapchainImageBuffer) {
                        image.type(KHROpenglEnable.XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_KHR);
                    }

                    xrCheck(XR10.xrEnumerateSwapchainImages(swapchainWrapper.handle, intBuf, XrSwapchainImageBaseHeader.create(swapchainImageBuffer.address(), swapchainImageBuffer.capacity())));
                    swapchainWrapper.images = swapchainImageBuffer;
                    swapchains[i] = swapchainWrapper;
                }
            }
        }
    }

    public boolean pollEvents() {
//        glfwPollEvents();
        XrEventDataBaseHeader event = readNextOpenXREvent();
        if (event == null) {
            return false;
        }

        do {
            switch (event.type()) {
                case XR10.XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING: {
                    XrEventDataInstanceLossPending instanceLossPending = XrEventDataInstanceLossPending.create(event.address());
                    System.err.printf("XrEventDataInstanceLossPending by %d\n", instanceLossPending.lossTime());
//            *requestRestart = true;
                    return true;
                }
                case XR10.XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED: {
                    XrEventDataSessionStateChanged sessionStateChangedEvent = XrEventDataSessionStateChanged.create(event.address());
                    return OpenXRHandleSessionStateChangedEvent(sessionStateChangedEvent/*, requestRestart*/);
                }
                case XR10.XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED:
                    break;
                case XR10.XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING:
                default: {
                    System.out.printf("Ignoring event type %d\n", event.type());
                    break;
                }
            }
            event = readNextOpenXREvent();
        }
        while (event != null);

        return false;
    }

    private XrEventDataBaseHeader readNextOpenXREvent() {
        // It is sufficient to just clear the XrEventDataBuffer header to
        // XR_TYPE_EVENT_DATA_BUFFER rather than recreate it every time

        eventDataBuffer.clear();
        eventDataBuffer.type(XR10.XR_TYPE_EVENT_DATA_BUFFER);
        int result = XR10.xrPollEvent(xrInstance, eventDataBuffer);
        xrCheck(result);
        if (result == XR10.XR_SUCCESS) {
            if (eventDataBuffer.type() == XR10.XR_TYPE_EVENT_DATA_EVENTS_LOST) {
                XrEventDataEventsLost dataEventsLost = XrEventDataEventsLost.create(eventDataBuffer.address());
                System.out.printf("%d events lost\n", dataEventsLost.lostEventCount());
            }

            return XrEventDataBaseHeader.create(eventDataBuffer.address());
        }
        if (result == XR10.XR_EVENT_UNAVAILABLE) {
            return null;
        }
        throw new IllegalStateException(String.format("[XrResult failure %d in xrPollEvent]", result));
    }

    boolean OpenXRHandleSessionStateChangedEvent(XrEventDataSessionStateChanged stateChangedEvent) {
        int oldState = sessionState;
        sessionState = stateChangedEvent.state();

        System.out.printf("XrEventDataSessionStateChanged: state %s->%s session=%d time=%d\n", oldState, sessionState, stateChangedEvent.session(), stateChangedEvent.time());

        if ((stateChangedEvent.session() != NULL) && (stateChangedEvent.session() != xrSession.address())) {
            System.err.println("XrEventDataSessionStateChanged for unknown session");
            return false;
        }

        switch (sessionState) {
            case XR10.XR_SESSION_STATE_READY: {
                assert (xrSession != null);
                XrSessionBeginInfo sessionBeginInfo = XrSessionBeginInfo.mallocStack();
                sessionBeginInfo.set(XR10.XR_TYPE_SESSION_BEGIN_INFO, 0, viewConfigType);
                xrCheck(XR10.xrBeginSession(xrSession, sessionBeginInfo));
                sessionRunning = true;
                return false;
            }
            case XR10.XR_SESSION_STATE_STOPPING: {
                assert (xrSession != null);
                sessionRunning = false;
                xrCheck(XR10.xrEndSession(xrSession));
                return false;
            }
            case XR10.XR_SESSION_STATE_EXITING: {
                // Do not attempt to restart because user closed this session.
//        *requestRestart = false;
                return true;
            }
            case XR10.XR_SESSION_STATE_LOSS_PENDING: {
                // Poll for a new instance.
//        *requestRestart = true;
                return true;
            }
            default:
                return false;
        }
    }


    public void renderFrameOpenXR() {
        try (MemoryStack stack = stackPush()) {
            XrFrameWaitInfo frameWaitInfo = XrFrameWaitInfo.callocStack(); //TODO wait until max(nextFrame, nextTick) this will need multithreading and most likely wont be a real issue for a while anyway
            frameWaitInfo.type(XR10.XR_TYPE_FRAME_WAIT_INFO);
            XrFrameState frameState = XrFrameState.callocStack();
            frameState.type(XR10.XR_TYPE_FRAME_STATE);
            xrCheck(XR10.xrWaitFrame(xrSession, frameWaitInfo, frameState));

            XrFrameBeginInfo frameBeginInfo = XrFrameBeginInfo.callocStack();
            frameBeginInfo.type(XR10.XR_TYPE_FRAME_BEGIN_INFO);
            xrCheck(XR10.xrBeginFrame(xrSession, frameBeginInfo));

            XrCompositionLayerProjection layerProjection = XrCompositionLayerProjection.callocStack();
            layerProjection.type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION);
            PointerBuffer layers = stack.callocPointer(1);

            if (frameState.shouldRender()) {
                if (renderLayerOpenXR(frameState.predictedDisplayTime(), layerProjection)) {
                    layers.put(layerProjection.address());
                }
            }
            layers.flip();

            XrFrameEndInfo frameEndInfo = XrFrameEndInfo.mallocStack();
            frameEndInfo.set(
                    XR10.XR_TYPE_FRAME_END_INFO,
                    0,
                    frameState.predictedDisplayTime(),
                    XR10.XR_ENVIRONMENT_BLEND_MODE_OPAQUE,
                    layers.limit(),
                    layers
            );

            if (layers.limit() > 0) {
                layerProjection.views().free(); //These values were allocated in a child function so they must be freed manually as we could not use the stack
            }

            xrCheck(XR10.xrEndFrame(xrSession, frameEndInfo));
        }
    }

    private boolean renderLayerOpenXR(long predictedDisplayTime, XrCompositionLayerProjection layer) {
        try (MemoryStack stack = stackPush()) {
            XrCompositionLayerProjectionView.Buffer projectionLayerViews;

            XrViewState viewState = new XrViewState(stack.calloc(XrViewState.SIZEOF));
            viewState.type(XR10.XR_TYPE_VIEW_STATE);
            IntBuffer intBuf = stack.mallocInt(1);

            XrViewLocateInfo viewLocateInfo = new XrViewLocateInfo(stack.malloc(XrViewLocateInfo.SIZEOF));
            viewLocateInfo.set(XR10.XR_TYPE_VIEW_LOCATE_INFO,
                    0,
                    viewConfigType,
                    predictedDisplayTime,
                    xrAppSpace
            );

            xrCheck(XR10.xrLocateViews(xrSession, viewLocateInfo, viewState, intBuf, views));

            if ((viewState.viewStateFlags() & XR10.XR_VIEW_STATE_POSITION_VALID_BIT) == 0 ||
                    (viewState.viewStateFlags() & XR10.XR_VIEW_STATE_ORIENTATION_VALID_BIT) == 0) {
                return false;  // There is no valid tracking poses for the views.
            }
            int viewCountOutput = intBuf.get(0);
            assert (viewCountOutput == views.capacity());
            assert (viewCountOutput == viewConfigs.capacity());
            assert (viewCountOutput == swapchains.length);
            assert (viewCountOutput == 2);

            projectionLayerViews = new XrCompositionLayerProjectionView.Buffer(mallocAndFillBufferHeap(viewCountOutput, XrCompositionLayerProjectionView.SIZEOF, XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW));

            // Update hand position based on the predicted time of when the frame will be rendered! This
            // should result in a more accurate location, and reduce perceived lag.
            if (sessionState == XR10.XR_SESSION_STATE_FOCUSED) {
                for (int i = 0; i < 2; i++) {
                    if (!inputState.renderHand[i]) {
                        continue;
                    }
//                    setPoseFromSpace(inputState.handSpace[i], predictedDisplayTime, inputState.handPose[i]);
                    setPoseFromSpace(inputState.handSpace[i], predictedDisplayTime, inputState.poses[i]);

                }
            }

            setPoseFromSpace(xrViewSpace, predictedDisplayTime, MineXRaftClient.headPose, MineXRaftClient.yawTurn);

            XrCamera camera = (XrCamera) MinecraftClient.getInstance().gameRenderer.getCamera();
            camera.updateXR(this.client.world, this.client.getCameraEntity() == null ? this.client.player : this.client.getCameraEntity(), MineXRaftClient.headPose);

            if (MinecraftClient.getInstance().player != null) {
                Entity player = MinecraftClient.getInstance().player;
                EntityExt ext = (EntityExt) player;

                float yawMc = MineXRaftClient.headPose.getYaw();
                float pitchMc = MineXRaftClient.headPose.getPitch();
                float dYaw = yawMc - ext.yaw();
                float dPitch = pitchMc - ext.pitch();
                ext.yaw(yawMc);
                ext.pitch(pitchMc);
                ext.prevYaw(ext.prevYaw() + dYaw);
                ext.prevPitch(MathHelper.clamp(ext.prevPitch() + dPitch, -90, 90));
                if (player.getVehicle() != null) {
                    player.getVehicle().onPassengerLookAround(player);
                }
            }

            long frameStartTime = Util.getMeasuringTimeNano();
            clientExt.preRenderXR(true, frameStartTime);

            // Render view to the appropriate part of the swapchain image.
            for (int viewIndex = 0; viewIndex < viewCountOutput; viewIndex++) {
                // Each view has a separate swapchain which is acquired, rendered to, and released.
                Swapchain viewSwapchain = swapchains[viewIndex];

                XrSwapchainImageAcquireInfo acquireInfo = new XrSwapchainImageAcquireInfo(stack.calloc(XrSwapchainImageAcquireInfo.SIZEOF));
                acquireInfo.type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO);

                xrCheck(XR10.xrAcquireSwapchainImage(viewSwapchain.handle, acquireInfo, intBuf));
                int swapchainImageIndex = intBuf.get(0);

                XrSwapchainImageWaitInfo waitInfo = new XrSwapchainImageWaitInfo(stack.malloc(XrSwapchainImageWaitInfo.SIZEOF));
                waitInfo.set(XR10.XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO, 0, XR10.XR_INFINITE_DURATION);

                xrCheck(XR10.xrWaitSwapchainImage(viewSwapchain.handle, waitInfo));

                XrCompositionLayerProjectionView projectionLayerView = projectionLayerViews.get(viewIndex);
                projectionLayerView.pose(views.get(viewIndex).pose());
                projectionLayerView.fov(views.get(viewIndex).fov());
                projectionLayerView.subImage().swapchain(viewSwapchain.handle);
                projectionLayerView.subImage().imageRect().offset().set(0, 0);
                projectionLayerView.subImage().imageRect().extent().set(viewSwapchain.width, viewSwapchain.height);

                {
                    XrSwapchainImageOpenGLKHR xrSwapchainImageOpenGLKHR = viewSwapchain.images.get(swapchainImageIndex);
                    ((FBAccessor) client.getFramebuffer()).setColorTexture(xrSwapchainImageOpenGLKHR.image());
                    MineXRaftClient.viewportRect = projectionLayerView.subImage().imageRect();
                    MineXRaftClient.tmpResetSize();
                    MineXRaftClient.fov = projectionLayerView.fov();
                    MineXRaftClient.eyePose.set(projectionLayerView.pose(), MineXRaftClient.yawTurn);
//                    MineXRaftClient.eyePose.set(projectionLayerView.pose());
                    MineXRaftClient.viewIndex = viewIndex;
                    if (camera.isReady()) {
                        camera.setEyePose(MineXRaftClient.eyePose, client.getTickDelta());
                    }
                    clientExt.doRenderXR(true, frameStartTime);
                    if (camera.isReady()) {
                        camera.popEyePose();
                    }
                    MineXRaftClient.fov = null;
                    MineXRaftClient.viewportRect = null;
                }

                XrSwapchainImageReleaseInfo releaseInfo = new XrSwapchainImageReleaseInfo(stack.calloc(XrSwapchainImageReleaseInfo.SIZEOF));
                releaseInfo.type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO);
                xrCheck(XR10.xrReleaseSwapchainImage(viewSwapchain.handle, releaseInfo));
            }
            clientExt.postRenderXR(true, frameStartTime);

            layer.space(xrAppSpace);
            layer.views(projectionLayerViews);
            return true;
        }
    }

    public static Matrix4f createProjectionFov(Matrix4f dest, XrFovf fov, float nearZ, float farZ) {
        try (MemoryStack stack = stackPush()) {
            float tanLeft = (float) Math.tan(fov.angleLeft());
            float tanRight = (float) Math.tan(fov.angleRight());
            float tanDown = (float) Math.tan(fov.angleDown());
            float tanUp = (float) Math.tan(fov.angleUp());
            float tanAngleWidth = tanRight - tanLeft;
            float tanAngleHeight = tanUp - tanDown;

            FloatBuffer m = stack.mallocFloat(16);
            m.put(0, 2.0f / tanAngleWidth);
            m.put(4, 0.0f);
            m.put(8, (tanRight + tanLeft) / tanAngleWidth);
            m.put(12, 0.0f);

            m.put(1, 0.0f);
            m.put(5, 2.0f / tanAngleHeight);
            m.put(9, (tanUp + tanDown) / tanAngleHeight);
            m.put(13, 0.0f);

            m.put(2, 0.0f);
            m.put(6, 0.0f);
            m.put(10, -(farZ + nearZ) / (farZ - nearZ));
            m.put(14, -(farZ * (nearZ + nearZ)) / (farZ - nearZ));

            m.put(3, 0.0f);
            m.put(7, 0.0f);
            m.put(11, -1.0f);
            m.put(15, 0.0f);

            //###

            m.put(0, 2.0f / tanAngleWidth);
            m.put(1, 0.0f);
            m.put(2, 0.0f);
            m.put(3, 0.0f);
            m.put(4, 0.0f);
            m.put(5, 2.0f / tanAngleHeight);
            m.put(6, 0.0f);
            m.put(7, 0.0f);
            m.put(8, (tanRight + tanLeft) / tanAngleWidth);
            m.put(9, (tanUp + tanDown) / tanAngleHeight);
            m.put(10, -(farZ + nearZ) / (farZ - nearZ));
            m.put(11, -1.0f);
            m.put(12, 0.0f);
            m.put(13, 0.0f);
            m.put(14, -(farZ * (nearZ + nearZ)) / (farZ - nearZ));
            m.put(15, 0.0f);
            return dest.set(m);
        }
    }

    public void xrCheck(int result) throws IllegalStateException {
        if (result >= 0) return;

        if (xrInstance != null) {
            ByteBuffer str = stackMalloc(XR10.XR_MAX_RESULT_STRING_SIZE);
            if (XR10.xrResultToString(xrInstance, result, str) >= 0) {
                throw new XrResultException(memUTF8Safe(str));
            }
        }
        throw new XrResultException("XR method returned " + result);
    }

    public static class XrResultException extends RuntimeException {
        public XrResultException(String s) {
            super(s);
        }
    }

    @SuppressWarnings("RedundantCast")
    public void makeActions() {
        try (MemoryStack stack = stackPush()) {
            inputState = new InputState();

            XrActionSetCreateInfo actionSetCreateInfo = XrActionSetCreateInfo.mallocStack().set(XR10.XR_TYPE_ACTION_SET_CREATE_INFO,
                    NULL,
                    memASCII("gameplay"),
                    memASCII("Gameplay"),
                    0
            );
            PointerBuffer pp = stackMallocPointer(1);
            xrCheck(XR10.xrCreateActionSet(xrInstance, actionSetCreateInfo, pp));
            inputState.actionSet = new XrActionSet(pp.get(0), xrSession.getCapabilities());

            xrCheck(XR10.xrStringToPath(xrInstance, "/user/hand/left", (LongBuffer) inputState.handSubactionPath.position(0)));
            xrCheck(XR10.xrStringToPath(xrInstance, "/user/hand/right", (LongBuffer) inputState.handSubactionPath.position(1)));
            inputState.handSubactionPath.rewind();

            // Create an action to track the position and orientation of the hands! This is
            // the controller location, or the center of the palms for actual hands.
            XrActionCreateInfo actionCreateInfo = XrActionCreateInfo.mallocStack().set(
                    XR10.XR_TYPE_ACTION_CREATE_INFO,
                    NULL,
                    memASCII("hand_pose"),
                    XR10.XR_ACTION_TYPE_POSE_INPUT,
                    inputState.handSubactionPath.capacity(),
                    inputState.handSubactionPath,
                    memASCII("Hand Pose")
            );
            xrCheck(XR10.xrCreateAction(inputState.actionSet, actionCreateInfo, pp));
            inputState.poseAction = new XrAction(pp.get(0), xrSession.getCapabilities());

            // Create an action for listening to the select action! This is primary trigger
            // on controllers, and an airtap on HoloLens
            actionCreateInfo.actionType(XR10.XR_ACTION_TYPE_BOOLEAN_INPUT);
            actionCreateInfo.actionName(memASCII("select"));
            actionCreateInfo.localizedActionName(memASCII("Select"));
            xrCheck(XR10.xrCreateAction(inputState.actionSet, actionCreateInfo, pp));
            inputState.triggerAction = new XrAction(pp.get(0), xrSession.getCapabilities());

            //Analog thumbstick action
            actionCreateInfo.actionType(XR10.XR_ACTION_TYPE_VECTOR2F_INPUT);
            actionCreateInfo.actionName(memASCII("thumbstick"));
            actionCreateInfo.localizedActionName(memASCII("Thumbstick"));
            xrCheck(XR10.xrCreateAction(inputState.actionSet, actionCreateInfo, pp));
            inputState.thumbstickAction = new XrAction(pp.get(0), xrSession.getCapabilities());

            //A button action
            actionCreateInfo.actionType(XR10.XR_ACTION_TYPE_BOOLEAN_INPUT);
            actionCreateInfo.actionName(memASCII("jump"));
            actionCreateInfo.localizedActionName(memASCII("Jump"));
            xrCheck(XR10.xrCreateAction(inputState.actionSet, actionCreateInfo, pp));
            inputState.aPressAction = new XrAction(pp.get(0), xrSession.getCapabilities());

            // Bind the actions we just created to specific locations on the Khronos simple_controller
            // definition! These are labeled as 'suggested' because they may be overridden by the runtime
            // preferences. For example, if the runtime allows you to remap buttons, or provides input
            // accessibility settings.
            LongBuffer profile_path = stackMallocLong(1);
            LongBuffer pose_path = stackMallocLong(2);
            LongBuffer triggerValue_path = stackMallocLong(2);
            LongBuffer selectClick_path = stackMallocLong(2);
            LongBuffer thumbstick_path = stackMallocLong(2);
            LongBuffer aButton_path = stackMallocLong(2);
            xrCheck(XR10.xrStringToPath(xrInstance, "/user/hand/left/input/grip/pose", (LongBuffer) pose_path.position(0)));
            xrCheck(XR10.xrStringToPath(xrInstance, "/user/hand/right/input/grip/pose", (LongBuffer) pose_path.position(1)));
            xrCheck(XR10.xrStringToPath(xrInstance, "/user/hand/left/input/select/click", (LongBuffer) selectClick_path.position(0)));
            xrCheck(XR10.xrStringToPath(xrInstance, "/user/hand/right/input/select/click", (LongBuffer) selectClick_path.position(1)));
            xrCheck(XR10.xrStringToPath(xrInstance, "/user/hand/right/input/trigger/value", (LongBuffer) triggerValue_path.position(0)));
            xrCheck(XR10.xrStringToPath(xrInstance, "/user/hand/right/input/trigger/value", (LongBuffer) triggerValue_path.position(1)));
            xrCheck(XR10.xrStringToPath(xrInstance, "/user/hand/left/input/thumbstick", (LongBuffer) thumbstick_path.position(0)));
            xrCheck(XR10.xrStringToPath(xrInstance, "/user/hand/right/input/thumbstick", (LongBuffer) thumbstick_path.position(1)));
            xrCheck(XR10.xrStringToPath(xrInstance, "/user/hand/left/input/x/click", (LongBuffer) aButton_path.position(0)));
            xrCheck(XR10.xrStringToPath(xrInstance, "/user/hand/right/input/a/click", (LongBuffer) aButton_path.position(1)));
            pose_path.rewind();
            selectClick_path.rewind();
            thumbstick_path.rewind();
            aButton_path.rewind();

//            {
//                xrCheck(XR10.xrStringToPath(xrInstance, "/interaction_profiles/khr/simple_controller", (LongBuffer) profile_path));
//                XrActionSuggestedBinding.Buffer bindings = XrActionSuggestedBinding.mallocStack(4);
//                bindings.get(0).set(inputState.poseAction, pose_path.get(0));
//                bindings.get(1).set(inputState.poseAction, pose_path.get(1));
//                bindings.get(2).set(inputState.selectAction, selectClick_path.get(0));
//                bindings.get(3).set(inputState.selectAction, selectClick_path.get(1));
//
//                XrInteractionProfileSuggestedBinding suggested_binds = XrInteractionProfileSuggestedBinding.mallocStack().set(
//                        XR10.XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING,
//                        NULL,
//                        profile_path.get(0),
//                        bindings
//                );
//
//                xrCheck(XR10.xrSuggestInteractionProfileBindings(xrInstance, suggested_binds));
//            }

            {
                xrCheck(XR10.xrStringToPath(xrInstance, "/interaction_profiles/oculus/touch_controller", (LongBuffer) profile_path));
                XrActionSuggestedBinding.Buffer bindings = XrActionSuggestedBinding.mallocStack(8);
                bindings.get(0).set(inputState.poseAction, pose_path.get(0));
                bindings.get(1).set(inputState.poseAction, pose_path.get(1));
                bindings.get(2).set(inputState.triggerAction, triggerValue_path.get(0));
                bindings.get(3).set(inputState.triggerAction, triggerValue_path.get(1));
                bindings.get(4).set(inputState.thumbstickAction, thumbstick_path.get(0));
                bindings.get(5).set(inputState.thumbstickAction, thumbstick_path.get(1));
                bindings.get(6).set(inputState.aPressAction, aButton_path.get(0));
                bindings.get(7).set(inputState.aPressAction, aButton_path.get(1));

                XrInteractionProfileSuggestedBinding suggested_binds = XrInteractionProfileSuggestedBinding.mallocStack().set(
                        XR10.XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING,
                        NULL,
                        profile_path.get(0),
                        bindings
                );

                xrCheck(XR10.xrSuggestInteractionProfileBindings(xrInstance, suggested_binds));
            }

            // Create frames of reference for the pose actions
            for (int i = 0; i < 2; i++) {
                XrActionSpaceCreateInfo action_space_info = XrActionSpaceCreateInfo.mallocStack().set(
                        XR10.XR_TYPE_ACTION_SPACE_CREATE_INFO,
                        NULL,
                        inputState.poseAction,
                        inputState.handSubactionPath.get(i),
                        identityPose
                );
                xrCheck(XR10.xrCreateActionSpace(xrSession, action_space_info, pp));
                inputState.handSpace[i] = new XrSpace(pp.get(0), xrSession);
            }

            // Attach the action set we just made to the session
            XrSessionActionSetsAttachInfo attach_info = XrSessionActionSetsAttachInfo.mallocStack().set(
                    XR10.XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO,
                    NULL,
                    stack.pointers(inputState.actionSet.address())
            );
            xrCheck(XR10.xrAttachSessionActionSets(xrSession, attach_info));
        }
    }

    public void pollActions() {
        if (sessionState != XR10.XR_SESSION_STATE_FOCUSED) {
            return;
        }

        try (MemoryStack stack = stackPush()) {
            // Update our action set with up-to-date input data!
            XrActiveActionSet.Buffer action_set = XrActiveActionSet.callocStack(1).actionSet(inputState.actionSet);

            XrActionsSyncInfo sync_info = XrActionsSyncInfo.mallocStack().set(
                    XR10.XR_TYPE_ACTIONS_SYNC_INFO,
                    NULL,
                    1,
                    action_set
            );

            xrCheck(XR10.xrSyncActions(xrSession, sync_info));

            // Now we'll get the current states of our actions, and store them for later use
            for (int hand = 0; hand < 2; hand++) {
                XrActionStateGetInfo get_info = XrActionStateGetInfo.mallocStack().set(
                        XR10.XR_TYPE_ACTION_STATE_GET_INFO,
                        NULL,
                        inputState.poseAction,
                        inputState.handSubactionPath.get(hand)
                );

                XrActionStatePose pose_state = XrActionStatePose.callocStack().type(XR10.XR_TYPE_ACTION_STATE_POSE);
                xrCheck(XR10.xrGetActionStatePose(xrSession, get_info, pose_state));
                inputState.renderHand[hand] = pose_state.isActive();

                // Events come with a timestamp
                XrActionStateBoolean select_state = XrActionStateBoolean.callocStack().type(XR10.XR_TYPE_ACTION_STATE_BOOLEAN);
                get_info.action(inputState.triggerAction);
                xrCheck(XR10.xrGetActionStateBoolean(xrSession, get_info, select_state));
                inputState.trigger[hand] = select_state.currentState();

                // If we have a select event, update the hand pose to match the event's timestamp
                if (select_state.changedSinceLastSync()) {
                    System.out.println(hand);
                    setPoseFromSpace(inputState.handSpace[hand], select_state.lastChangeTime(), inputState.poses[hand]);
                    if (select_state.currentState()) {
                        MineXRaftClient.yawTurn += Math.toRadians(22);
                        Vector3f rotatedPos = new Quaternionf().rotateLocalY(MineXRaftClient.yawTurn).transform(MineXRaftClient.headPose.rawPos, new Vector3f());
                        Vector3f finalPos = MineXRaftClient.xrOffset.add(MineXRaftClient.headPose.getPos(), new Vector3f());
//                        System.out.println("Current / Wanted pos" + finalPos.toString());
//                        System.out.println("Raw pos" + MineXRaftClient.headPose.rawPos.toString());
//                        System.out.println("Rotated pos" + rotatedPos.toString());
                        MineXRaftClient.xrOffset = finalPos.sub(rotatedPos).mul(1, 0, 1);
                    }
                }

                XrActionStateVector2f thumbstick_state = XrActionStateVector2f.callocStack().type(XR10.XR_TYPE_ACTION_STATE_VECTOR2F);
                get_info.action(inputState.thumbstickAction);
                xrCheck(XR10.xrGetActionStateVector2f(xrSession, get_info, thumbstick_state));
                if (thumbstick_state.changedSinceLastSync()) {
                    inputState.handThumbstick[hand].set(thumbstick_state.currentState());
                    System.out.printf("X:%f Y:%f\n", thumbstick_state.currentState().x(), thumbstick_state.currentState().y());
                }

                XrActionStateBoolean abutton_state = XrActionStateBoolean.callocStack().type(XR10.XR_TYPE_ACTION_STATE_BOOLEAN);
                get_info.action(inputState.aPressAction);
                xrCheck(XR10.xrGetActionStateBoolean(xrSession, get_info, abutton_state));
                inputState.aDown[hand] = abutton_state.currentState();
            }
        }
    }

    public void setPoseFromSpace(XrSpace handSpace, long time, Pose result) {
        try (MemoryStack ignored = stackPush()) {
            XrSpaceLocation space_location = XrSpaceLocation.callocStack().type(XR10.XR_TYPE_SPACE_LOCATION);
            int res = xrLocateSpace(handSpace, xrAppSpace, time, space_location);
            if (res == XR10.XR_SUCCESS &&
                    (space_location.locationFlags() & XR10.XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0 &&
                    (space_location.locationFlags() & XR10.XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0) {

                result.set(space_location.pose());
            }
        }
    }

    public void setPoseFromSpace(XrSpace handSpace, long time, Pose result, float turnYaw) {
        try (MemoryStack ignored = stackPush()) {
            XrSpaceLocation space_location = XrSpaceLocation.callocStack().type(XR10.XR_TYPE_SPACE_LOCATION);
            int res = xrLocateSpace(handSpace, xrAppSpace, time, space_location);
            if (res == XR10.XR_SUCCESS &&
                    (space_location.locationFlags() & XR10.XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0 &&
                    (space_location.locationFlags() & XR10.XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0) {

                result.set(space_location.pose(), turnYaw);
            }
        }
    }

    private static final long vm = DynCall.dcNewCallVM(4096);

    /**
     * Minecraft uses an old version of lwjgl so we have to create custom methods to call certain native functions
     */
    private int xrLocateSpace(XrSpace space, XrSpace baseSpace, @NativeType("XrTime") long time, @NativeType("XrSpaceLocation *") XrSpaceLocation location) {
        long __functionAddress = space.getCapabilities().xrLocateSpace;
//        return JNI.callPPJPI(space.address(), baseSpace.address(), time, location, __functionAddress);
        DynCall.dcMode(vm, DynCall.DC_CALL_C_DEFAULT);
        DynCall.dcReset(vm);
        DynCall.dcArgPointer(vm, space.address());
        DynCall.dcArgPointer(vm, baseSpace.address());
        DynCall.dcArgLongLong(vm, time);
        DynCall.dcArgPointer(vm, location.address());
        return DynCall.dcCallInt(vm, __functionAddress);
    }
}