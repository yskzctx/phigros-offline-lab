//! Offline-only chapter entry. This code is built into the separate child-process
//! player, never into the original Phira process. No account/client setup or upload.
use anyhow::{bail, Context, Result};
use jni::{objects::JClass, objects::JString, EnvUnowned};
use macroquad::prelude::*;
use once_cell::sync::Lazy;
use prpr::{config::{Config, Mods}, core::{BOLD_FONT, PGR_FONT}, fs::{fs_from_file, load_info},
    scene::{LoadingScene, GameMode, Main, NextScene, Scene}, time::TimeManager,
    ui::{FontArc, TextPainter, Ui}};
use serde::{Deserialize, Serialize};
use std::{any::Any, path::{Path, PathBuf}, sync::Mutex};

#[derive(Clone, Default, Deserialize, Serialize)]
pub struct ChapterRequest {
    pub directory: String,
    pub data_root: String,
    pub autoplay: bool,
}
static REQUEST: Lazy<Mutex<Option<ChapterRequest>>> = Lazy::new(|| Mutex::new(None));

fn validate(request: &ChapterRequest) -> Result<(PathBuf, PathBuf)> {
    let root=std::fs::canonicalize(&request.data_root).context("chapter root missing")?;
    let path=std::fs::canonicalize(&request.directory).context("chapter directory missing")?;
    if !path.starts_with(&root)||path==root||!path.is_dir(){bail!("chart directory outside local chapter root");}
    Ok((root,path))
}

#[cfg(target_os="android")]
#[no_mangle]
pub extern "C" fn Java_com_phigros_offline_ChapterPlayerActivity_configureChapter(_env: EnvUnowned,_class:JClass,request:JString)->bool {
    let result=serde_json::from_str::<ChapterRequest>(&request.to_string()).and_then(|r|{
        if validate(&r).is_err(){return Err(serde::de::Error::custom("invalid chapter path"));}
        *REQUEST.lock().unwrap()=Some(r);Ok(())
    });result.is_ok()
}

pub fn requested()->bool {REQUEST.lock().unwrap().is_some()}

#[cfg(target_os="android")]
fn finish_activity(){
    use jni::{jni_sig,jni_str,objects::JObject,vm::JavaVM};
    if let Some(vm)=JavaVM::singleton(){let _=vm.attach_current_thread(|env|->jni::errors::Result<()>{
        let context=unsafe{JObject::from_raw(env,ndk_context::android_context().context() as _)};
        env.call_method(context,jni_str!("finishChapter"),jni_sig!("()V"),&[])?;Ok(())
    });}
}
#[cfg(not(target_os="android"))]
fn finish_activity(){}

struct ReturnScene {next:Option<Box<dyn Scene>>,started:bool,done:bool}
impl Scene for ReturnScene {
    fn enter(&mut self,_:&mut TimeManager,_:Option<RenderTarget>)->Result<()>{if self.started{self.done=true;}Ok(())}
    fn update(&mut self,_:&mut TimeManager)->Result<()>{Ok(())}
    fn render(&mut self,_:&mut TimeManager,ui:&mut Ui)->Result<()>{ui.text("本地自制章节").pos(0.,0.).anchor(0.5,0.5).size(0.7).draw();Ok(())}
    fn on_result(&mut self,_:&mut TimeManager,_:Box<dyn Any>)->Result<()>{self.done=true;Ok(())}
    fn next_scene(&mut self,_:&mut TimeManager)->NextScene{
        if let Some(scene)=self.next.take(){self.started=true;return NextScene::Overlay(scene);}
        if self.done {NextScene::Exit}else{NextScene::None}
    }
}

pub async fn run()->Result<()> {
    let request=REQUEST.lock().unwrap().clone().context("no chapter request")?;
    let (_root,path)=validate(&request)?;
    prpr::core::init_assets();
    let runtime=tokio::runtime::Builder::new_multi_thread().enable_all().build()?;let _guard=runtime.enter();
    let font=FontArc::try_from_vec(load_file("font.ttf").await?)?;
    let bold=FontArc::try_from_vec(load_file("bold.ttf").await?)?;
    let pgr=FontArc::try_from_vec(load_file("phigros.ttf").await?)?;
    BOLD_FONT.with(|f|*f.borrow_mut()=Some(TextPainter::new(bold,None)));
    PGR_FONT.with(|f|*f.borrow_mut()=Some(TextPainter::new(pgr,None)));
    let mut painter=TextPainter::new(font,None);
    let mut fs=fs_from_file(Path::new(&path))?;
    let mut info=load_info(fs.as_mut()).await?;
    // This entry accepts only locally authored bundles. Do not convert an online
    // identity/unlock resource into an unlocked local chart by stripping fields.
    if info.id.is_some()||info.uploader.is_some()||info.unlock_video.is_some(){bail!("online identity or unlock video is not supported in this local chapter entry");}
    let mut config=Config::default();config.offline_mode=true;config.mp_enabled=false;config.player_name="本地自制章节".into();
    config.mods.set(Mods::AUTOPLAY,request.autoplay);
    let record_path=path.join("local-record.json");
    let save=if request.autoplay{None}else{Some(Box::new(move |record:prpr::scene::SimpleRecord|->Result<()>{
        let bytes=serde_json::to_vec(&record)?;let tmp=record_path.with_extension("json.tmp");std::fs::write(&tmp,bytes)?;std::fs::rename(tmp,&record_path)?;Ok(())
    }) as prpr::scene::SaveFn)};
    let scene=LoadingScene::new(GameMode::NoRetry,info,config,fs,None,None,None,save,None).await?;
    let (tx,rx)=std::sync::mpsc::channel();*crate::MESSAGES_TX.lock().unwrap()=Some(tx);
    unsafe{get_internal_gl()}.quad_context.display_mut().set_pause_resume_listener(crate::on_pause_resume);
    let mut main=Main::new(Box::new(ReturnScene{next:Some(Box::new(scene)),started:false,done:false}),TimeManager::default(),None).await?;
    let mut paused=false;
    loop {
        for state in rx.try_iter(){if state&&!paused{main.pause()?;paused=true;}else if !state&&paused{main.resume()?;paused=false;}}
        if paused{std::thread::sleep(std::time::Duration::from_millis(16));next_frame().await;continue;}
        clear_background(BLACK);main.update()?;main.render(&mut painter)?;
        if main.should_exit(){break;}next_frame().await;
    }
    finish_activity();Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn reject_directory_outside_root(){let root=tempfile::tempdir().unwrap();let other=tempfile::tempdir().unwrap();let r=ChapterRequest{directory:other.path().to_string_lossy().into(),data_root:root.path().to_string_lossy().into(),autoplay:false};assert!(validate(&r).is_err());}
    #[test] fn accept_owned_chart_subdirectory(){let root=tempfile::tempdir().unwrap();let p=root.path().join("charts/local-1");std::fs::create_dir_all(&p).unwrap();let r=ChapterRequest{directory:p.to_string_lossy().into(),data_root:root.path().to_string_lossy().into(),autoplay:false};assert_eq!(validate(&r).unwrap().1,std::fs::canonicalize(p).unwrap());}
}
