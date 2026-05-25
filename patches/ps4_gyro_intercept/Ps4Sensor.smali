.class public Lcom/sdf/ps4/Ps4Sensor;
.super Ljava/lang/Object;
.source "Ps4Sensor.java"


# static fields
.field private static volatile appCtx:Landroid/content/Context;


# direct methods
.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static init(Landroid/content/Context;)V
    .locals 1
    if-nez p0, :cond_0
    return-void
    :cond_0
    invoke-virtual {p0}, Landroid/content/Context;->getApplicationContext()Landroid/content/Context;
    move-result-object v0
    sput-object v0, Lcom/sdf/ps4/Ps4Sensor;->appCtx:Landroid/content/Context;
    return-void
.end method

# Returns either the controller's SensorManager (if a paired gamepad exposes the
# requested sensor type via InputDevice.getSensorManager(), API 31+) or the
# original phone SensorManager passed in. Never returns null.
.method public static pick(Landroid/hardware/SensorManager;I)Landroid/hardware/SensorManager;
    .locals 7

    # API guard: InputDevice.getSensorManager added in API 31 (Android 12)
    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I
    const/16 v1, 0x1f
    if-ge v0, v1, :cond_check
    return-object p0

    :cond_check
    sget-object v0, Lcom/sdf/ps4/Ps4Sensor;->appCtx:Landroid/content/Context;
    if-nez v0, :cond_have_ctx
    return-object p0

    :cond_have_ctx
    :try_start_0
    const-string v1, "input"
    invoke-virtual {v0, v1}, Landroid/content/Context;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;
    move-result-object v1
    check-cast v1, Landroid/hardware/input/InputManager;

    if-nez v1, :cond_have_im
    return-object p0

    :cond_have_im
    invoke-virtual {v1}, Landroid/hardware/input/InputManager;->getInputDeviceIds()[I
    move-result-object v2

    if-nez v2, :cond_have_ids
    return-object p0

    :cond_have_ids
    array-length v3, v2
    const/4 v4, 0x0

    :goto_loop
    if-ge v4, v3, :cond_done
    aget v5, v2, v4

    invoke-virtual {v1, v5}, Landroid/hardware/input/InputManager;->getInputDevice(I)Landroid/view/InputDevice;
    move-result-object v5
    if-nez v5, :cond_skip
    goto :inc

    :cond_skip
    # Skip the phone's own virtual sensor InputDevice by requiring SOURCE_JOYSTICK
    # bit (0x01000010). DS4/Xbox controllers have it; touchscreens don't.
    invoke-virtual {v5}, Landroid/view/InputDevice;->getSources()I
    move-result v6
    const v0, 0x1000010
    and-int/2addr v6, v0
    if-eqz v6, :inc

    invoke-virtual {v5}, Landroid/view/InputDevice;->getSensorManager()Landroid/hardware/SensorManager;
    move-result-object v6
    if-eqz v6, :inc

    invoke-virtual {v6, p1}, Landroid/hardware/SensorManager;->getDefaultSensor(I)Landroid/hardware/Sensor;
    move-result-object v0
    if-eqz v0, :inc

    return-object v6

    :inc
    add-int/lit8 v4, v4, 0x1
    goto :goto_loop

    :cond_done
    return-object p0
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0

    :catch_0
    move-exception v0
    return-object p0
.end method
