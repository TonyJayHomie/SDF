.class public final Lcom/ik/simwheel/MainActivity;
.super Ly1/c;
.source "SourceFile"


# direct methods
.method public constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ly1/c;-><init>()V

    return-void
.end method


# virtual methods
.method public onCreate(Landroid/os/Bundle;)V
    .locals 0

    invoke-super {p0, p1}, Ly1/c;->onCreate(Landroid/os/Bundle;)V

    invoke-static {p0}, Lcom/sdf/ps4/Ps4Sensor;->init(Landroid/content/Context;)V

    invoke-static {p0}, Lcom/ik/simwheel/bridge/ControllerBridge;->ensureInitialized(Landroid/content/Context;)V

    invoke-static {p0}, Lcom/ik/simwheel/bridge/ControllerBridge;->attachOverlay(Landroid/app/Activity;)V

    return-void
.end method

.method public dispatchKeyEvent(Landroid/view/KeyEvent;)Z
    .locals 1

    invoke-static {p0, p1}, Lcom/ik/simwheel/bridge/ControllerBridge;->handleKeyEvent(Landroid/content/Context;Landroid/view/KeyEvent;)Z

    move-result v0

    if-eqz v0, :cond_0

    const/4 v0, 0x1

    return v0

    :cond_0
    invoke-super {p0, p1}, Ly1/c;->dispatchKeyEvent(Landroid/view/KeyEvent;)Z

    move-result p1

    return p1
.end method

.method public dispatchGenericMotionEvent(Landroid/view/MotionEvent;)Z
    .locals 1

    invoke-static {p0, p1}, Lcom/ik/simwheel/bridge/ControllerBridge;->handleMotionEvent(Landroid/content/Context;Landroid/view/MotionEvent;)Z

    move-result v0

    if-eqz v0, :cond_0

    const/4 v0, 0x1

    return v0

    :cond_0
    invoke-super {p0, p1}, Ly1/c;->dispatchGenericMotionEvent(Landroid/view/MotionEvent;)Z

    move-result p1

    return p1
.end method
