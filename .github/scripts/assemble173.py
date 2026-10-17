"""One-off asset assembly. Verify every byte and leave the final draft unpublished."""
import hashlib,json,os,re,subprocess,tempfile,time,urllib.request,urllib.parse
from pathlib import Path
repo=os.environ['GITHUB_REPOSITORY'];assert repo=='yskzctx/phigros-offline-lab'
manifest=json.loads(Path('.github/release173-relay.json').read_text())
assert manifest['relay_release_id']==415584567 and manifest['target_release_id']==415584493
def api(route):return json.loads(subprocess.check_output(['gh','api',route],text=True))
relay=api(f'repos/{repo}/releases/415584567');target=api(f'repos/{repo}/releases/415584493')
assert relay['draft']and relay['tag_name']=='transfer-173-20261017'
assert target['draft']and not target['prerelease']and target['tag_name']=='v173'
assert target['target_commitish']==manifest['source_commit']
class SafeRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,request,fp,code,msg,headers,newurl):
  redirected=super().redirect_request(request,fp,code,msg,headers,newurl)
  if redirected and urllib.parse.urlsplit(newurl).hostname!='api.github.com':redirected.remove_header('Authorization')
  return redirected
opener=urllib.request.build_opener(SafeRedirect())
assert len(manifest['parts'])==3
with tempfile.TemporaryDirectory(prefix='release173-')as temp:
 deadline=time.monotonic()+20*60
 for part in manifest['parts']:
  assert re.fullmatch(r'Phigros-offline-v173\.7z\.00[123]',part['name'])
  last_count=-1
  while True:
   live=api(f'repos/{repo}/releases/415584567');assert live['draft']and live['tag_name']=='transfer-173-20261017'
   assets={a['name']:a for a in live['assets']}
   available=sum(c['name']in assets and assets[c['name']]['state']=='uploaded'for c in part['chunks'])
   if available==len(part['chunks']):break
   assert time.monotonic()<deadline,'Temporary upload deadline exceeded'
   if available!=last_count:print(f"Waiting for {part['name']}: {available}/{len(part['chunks'])} chunks",flush=True);last_count=available
   time.sleep(10)
  path=Path(temp)/part['name'];whole=hashlib.sha256();size=0
  with path.open('wb')as output:
   for chunk in part['chunks']:
    assert re.fullmatch(r'p00[123]-c[0-9]{3}\.bin',chunk['name'])
    assert 0<chunk['bytes']<=64*1024*1024
    asset=assets[chunk['name']];assert asset['state']=='uploaded'and asset['size']==chunk['bytes']and asset['digest']=='sha256:'+chunk['sha256']
    assert asset['url'].startswith(f'https://api.github.com/repos/{repo}/releases/assets/')
    request=urllib.request.Request(asset['url'],headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Accept':'application/octet-stream','User-Agent':'Phigros-173-transfer'})
    digest=hashlib.sha256();received=0
    with opener.open(request,timeout=60)as response:
     while block:=response.read(1024*1024):
      received+=len(block);assert received<=chunk['bytes'];output.write(block);digest.update(block);whole.update(block)
    assert received==chunk['bytes']and digest.hexdigest()==chunk['sha256'];size+=received
  assert size==part['bytes']and whole.hexdigest()==part['sha256']
  target=api(f'repos/{repo}/releases/415584493');assert target['draft']and target['tag_name']=='v173'
  existing=next((a for a in target['assets']if a['name']==part['name']),None)
  if existing:assert existing['size']==size and existing['digest']=='sha256:'+part['sha256']
  else:subprocess.run(['gh','release','upload','v173','-R',repo,str(path)],check=True)
  path.unlink();print('Assembled, verified and uploaded '+part['name'],flush=True)
 final=api(f'repos/{repo}/releases/415584493')
 for part in manifest['parts']:
  asset=next(a for a in final['assets']if a['name']==part['name']);assert asset['size']==part['bytes']and asset['digest']=='sha256:'+part['sha256']
print('All three volumes verified; final release remains a draft for final checks',flush=True)
