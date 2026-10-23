"""Exercise the production configureChapter JNI entry in a fresh desktop JVM.

Uses the exact request/validation/entry source, with rendering imports omitted.
This checks JNI startup and containment, not Android rendering/gameplay.
"""
from pathlib import Path
import os, subprocess, sys

repo = Path(__file__).resolve().parents[2]
build = Path(sys.argv[1]).resolve()
build.mkdir(parents=True, exist_ok=True)
code = (repo/'chapter-player/local_chapter.rs').read_text(encoding='utf-8')
entry = code[code.index('#[derive(Clone, Default'):code.index('pub fn requested()')]
entry = entry.replace('#[cfg(target_os="android")]\n', '')
entry = entry.replace('prpr::chapter_controls::planner::', 'planner::')
(build/'src').mkdir(exist_ok=True)
(build/'src/lib.rs').write_text('use anyhow::{Context,Result};\nuse jni::{objects::{JClass,JString},EnvUnowned};\nuse once_cell::sync::Lazy;\nuse serde::{Deserialize,Serialize};\nuse std::{path::{Path,PathBuf},sync::Mutex};\n'
    +f'#[path={str(repo/"chapter-player/path_scope.rs")!r}] mod path_scope;\n'.replace("'", '"').replace('\\', '/')
    +f'#[path={str(repo/"chapter-player/planner.rs")!r}] mod planner;\n'.replace("'", '"').replace('\\', '/')
    +entry, encoding='utf-8')
(build/'Cargo.toml').write_text('''[package]
name="chapter-jni-regression"
version="0.1.0"
edition="2021"
[lib]
crate-type=["cdylib"]
[dependencies]
anyhow="1"
jni="=0.22.4"
once_cell="1"
serde={version="1",features=["derive"]}
serde_json="1"
''',encoding='utf-8')
java = build/'com/phigros/offline/ChapterPlayerActivity.java'
java.parent.mkdir(parents=True,exist_ok=True)
java.write_text(r'''package com.phigros.offline;
import java.nio.file.*;
public class ChapterPlayerActivity {
 private static native boolean configureChapter(String request)throws java.io.IOException;
 private static void reject(String request,String expected)throws Exception{
  try{configureChapter(request);throw new AssertionError("invalid request did not report error");}
  catch(java.io.IOException e){if(!e.getMessage().contains(expected))throw new AssertionError("wrong error category: "+e.getMessage());}
 }
 private static String q(Path p){return p.toAbsolutePath().toString().replace("\\","\\\\");}
 public static void main(String[] args)throws Exception {
  System.load(args[0]);
  Path root=Files.createTempDirectory("chapter-jni-");
  Path chart=Files.createDirectory(root.resolve("自制谱"));
  String request="{\"data_root\":\""+q(root)+"\",\"directory\":\""+q(chart)+"\",\"autoplay\":false}";
  if(!configureChapter(request))throw new AssertionError("fresh JVM rejected owned Unicode chart: JNI entry failed before rendering");
  reject("{}","参数");
  String outside=request.replace(q(chart),q(root.getParent()));
  reject(outside,"谱包目录");
  String invalidControls=request.substring(0,request.length()-1)+",\"controls\":{\"mode\":0,\"target\":1000000,\"accuracy\":10000,\"rules\":\"broken\"}}";
  if(!configureChapter(invalidControls))throw new AssertionError("inactive rules blocked manual gameplay");
  reject(invalidControls.replace("\"autoplay\":false","\"autoplay\":true"),"判定配置");
  if(!configureChapter(request))throw new AssertionError("valid request after rejection failed");
  Files.delete(chart);Files.delete(root);
  System.out.println("Fresh JVM: first JNI request, Unicode path, malformed JSON, containment and retry passed");
 }
}
''',encoding='utf-8')
subprocess.run(['cargo','build','--manifest-path',str(build/'Cargo.toml')],check=True)
subprocess.run(['javac','-encoding','UTF-8','-d',str(build),str(java)],check=True)
lib = build/'target/debug'/('chapter_jni_regression.dll' if os.name=='nt' else 'libchapter_jni_regression.so')
subprocess.run(['java','-cp',str(build),'com.phigros.offline.ChapterPlayerActivity',str(lib)],check=True)
