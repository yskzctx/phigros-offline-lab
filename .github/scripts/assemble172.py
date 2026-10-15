"""One manually dispatched transfer; never publish the draft or change game code."""
import hashlib,json,os,re,subprocess,tempfile,urllib.request
from pathlib import Path

repo=os.environ['GITHUB_REPOSITORY'];assert repo=='yskzctx/phigros-offline-lab'
manifest=json.loads(Path('.github/release172-relay.json').read_text())
assert manifest['relay_release_id']==413658150 and manifest['target_release_id']==413645794

def api(route):
    return json.loads(subprocess.check_output(['gh','api',route],text=True))

relay=api(f'repos/{repo}/releases/413658150')
target=api(f'repos/{repo}/releases/413645794')
assert relay['draft'] and relay['tag_name']=='transfer-172-20261015'
assert target['draft'] and target['prerelease'] and target['tag_name']=='v172-pre.1'
assets={a['name']:a for a in relay['assets']}
assert len(manifest['parts'])==3

class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,request,fp,code,msg,headers,newurl):
        redirected=super().redirect_request(request,fp,code,msg,headers,newurl)
        if redirected and urllib.parse.urlsplit(newurl).hostname!='api.github.com':
            redirected.remove_header('Authorization')
        return redirected

opener=urllib.request.build_opener(SafeRedirect())
with tempfile.TemporaryDirectory(prefix='release172-')as temp:
    for part in manifest['parts']:
        assert re.fullmatch(r'Phigros-offline-172-pre\.1\.7z\.00[123]',part['name'])
        path=Path(temp)/part['name'];whole=hashlib.sha256();size=0
        with path.open('wb')as output:
            for chunk in part['chunks']:
                assert re.fullmatch(r'p00[123]-c[0-9]{3}\.bin',chunk['name'])
                asset=assets[chunk['name']]
                assert asset['state']=='uploaded' and asset['size']==chunk['bytes']
                assert asset['digest']=='sha256:'+chunk['sha256']
                request=urllib.request.Request(asset['url'],headers={
                    'Authorization':'Bearer '+os.environ['GH_TOKEN'],
                    'Accept':'application/octet-stream','User-Agent':'Phigros-172-transfer'})
                digest=hashlib.sha256();received=0
                with opener.open(request,timeout=60)as response:
                    while block:=response.read(1024*1024):
                        received+=len(block);assert received<=chunk['bytes']
                        output.write(block);digest.update(block);whole.update(block)
                assert received==chunk['bytes'] and digest.hexdigest()==chunk['sha256']
                size+=received
        assert size==part['bytes'] and whole.hexdigest()==part['sha256']
        existing=next((a for a in api(f'repos/{repo}/releases/413645794')['assets']if a['name']==part['name']),None)
        if existing:
            assert existing['size']==size and existing['digest']=='sha256:'+part['sha256']
        else:
            subprocess.run(['gh','release','upload','v172-pre.1','-R',repo,str(path)],check=True)
        path.unlink()
        print('Assembled, verified and uploaded '+part['name'],flush=True)
    final=api(f'repos/{repo}/releases/413645794')
    for part in manifest['parts']:
        asset=next(a for a in final['assets']if a['name']==part['name'])
        assert asset['size']==part['bytes'] and asset['digest']=='sha256:'+part['sha256']
print('All three original volumes verified; target remains an unpublished prerelease draft',flush=True)
