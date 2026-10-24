"""Build just the LSPosed adapter. Android SDK/NDK, Xposed API and signing files are external."""
import argparse,hashlib,json,os,subprocess,zipfile
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--sdk',required=True,type=Path);p.add_argument('--ndk',required=True,type=Path);p.add_argument('--xposed-api',required=True,type=Path)
p.add_argument('--keystore',required=True,type=Path);p.add_argument('--alias',required=True);p.add_argument('--password-file',required=True,type=Path)
p.add_argument('--out',required=True,type=Path);p.add_argument('--build-tools',default='36.0.0');p.add_argument('--platform',default='android-36')
a=p.parse_args();root=Path(__file__).resolve().parent;out=a.out.resolve();out.mkdir(parents=True,exist_ok=True);build=out/'module-build';build.mkdir(exist_ok=True)
tools=a.sdk.resolve()/'build-tools'/a.build_tools;android=a.sdk.resolve()/'platforms'/a.platform/'android.jar';api=a.xposed_api.resolve()
for file in [android,api,a.keystore,a.password_file]:
 if not file.is_file():raise FileNotFoundError(file)
def run(*args):subprocess.run([str(v)for v in args],check=True)
classes=build/'classes';classes.mkdir(exist_ok=True);dex=build/'dex';dex.mkdir(exist_ok=True)
run('javac','--release','8','-encoding','UTF-8','-classpath',str(android)+os.pathsep+str(api),'-d',classes,*sorted((root/'src').glob('*.java')))
run('java','-Djava.io.tmpdir='+str(build),'-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','26','--lib',android,'--lib',api,'--output',dex,*sorted(classes.rglob('*.class')))
host='windows-x86_64'if os.name=='nt'else'linux-x86_64';compiler=a.ndk.resolve()/'toolchains/llvm/prebuilt'/host/'bin'/('aarch64-linux-android26-clang.cmd'if os.name=='nt'else'aarch64-linux-android26-clang')
native=build/'libphira_lab.so';run(compiler,'-shared','-fPIC','-O2','-std=c11','-Wall','-Wextra','-Werror','-fstack-protector-strong','-Wl,-z,max-page-size=16384','-Wl,-z,relro,-z,now','-o',native,root/'native/module.c','-llog','-ldl')
resources=build/'resources.apk';run(tools/('aapt2.exe'if os.name=='nt'else'aapt2'),'link','-I',android,'--manifest',root/'AndroidManifest.xml','-o',resources)
unsigned=build/'unsigned.apk';aligned=build/'aligned.apk'
with zipfile.ZipFile(resources)as res,zipfile.ZipFile(unsigned,'w')as z:
 for e in res.infolist():z.writestr(e,res.read(e))
 z.write(dex/'classes.dex','classes.dex',compress_type=zipfile.ZIP_DEFLATED);z.write(native,'lib/arm64-v8a/libphira_lab.so',compress_type=zipfile.ZIP_STORED)
 z.writestr('assets/xposed_init','com.phira.lab.Entry\n');z.writestr('assets/native_init','libphira_lab.so\n');z.writestr('assets/xposed_scope','org.flos.phira\n')
zipalign=tools/('zipalign.exe'if os.name=='nt'else'zipalign');run(zipalign,'-P','16','-f','4',unsigned,aligned)
apk=out/'Phira-Lab-LSPosed-0.8.2-arm64-v0.1-DEV.apk'
run('java','-Djava.io.tmpdir='+str(build),'-jar',tools/'lib/apksigner.jar','sign','--ks',a.keystore.resolve(),'--ks-key-alias',a.alias,'--ks-pass','file:'+str(a.password_file.resolve()),'--min-sdk-version','26','--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','true','--v4-signing-enabled','false','--out',apk,aligned)
run('java','-jar',tools/'lib/apksigner.jar','verify','--verbose',apk);run(zipalign,'-c','-P','16','4',apk)
with apk.open('rb')as f:sha=hashlib.file_digest(f,'sha256').hexdigest()
(out/'SHA256SUMS.txt').write_text(sha+'  '+apk.name+'\n',encoding='utf-8')
for f in [unsigned,aligned,resources]:f.unlink()
print(json.dumps(dict(apk=str(apk),sha256=sha,device_tested=False,complete_feature_parity=False)))
