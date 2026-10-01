Events.on(EventType.ClientLoadEvent, () => {
    const welcomeDialog = new BaseDialog(@界面名称);
    const cont = welcomeDialog.cont;
    cont.add(@开始界面).row();
    cont.button(@关闭界面, () => welcomeDialog.hide()).size(120, 50);
    welcomeDialog.show();
});