"""Read-only, bounded-memory validation. Never downloads, deletes or repairs files."""
import argparse,hashlib,json
from pathlib import Path
VOLUMES=[
 ('Phigros-offline-v173.7z.001',1073741824,'f0c15ffad998951d51597f1ffde44aa1a15d773ec6a54efcd6e60ec695643708'),
 ('Phigros-offline-v173.7z.002',1073741824,'d7808ca9a9b612e76c684852b19c127ab418c32246689c27a946f45a76682a65'),
 ('Phigros-offline-v173.7z.003',667241285,'636abea5dc718ad2b79d8b1b7df27d421a032c97e6eb6aebe3059b576eac8cfe'),
]
def verify(path,size,expected):
 result={'file':path.name,'expected_bytes':size,'expected_sha256':expected}
 if not path.is_file():return dict(result,status='missing')
 actual=path.stat().st_size;result['actual_bytes']=actual
 if actual!=size:return dict(result,status='size_mismatch')
 digest=hashlib.sha256()
 with path.open('rb')as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
 value=digest.hexdigest();result['actual_sha256']=value
 return dict(result,status='ok'if value==expected else 'hash_mismatch')
def main():
 parser=argparse.ArgumentParser(description='校验 v173 分卷；文件大小正确也可能内容损坏。仅读取文件，不上传。')
 parser.add_argument('directory',type=Path);args=parser.parse_args()
 results=[verify(args.directory/name,size,digest)for name,size,digest in VOLUMES]
 print(json.dumps(results,ensure_ascii=False,indent=2))
 bad=[r['file']for r in results if r['status']!='ok']
 if bad:print('需要重新下载或重新复制的分卷：'+', '.join(bad));return 1
 print('三个分卷逐字节 SHA-256 均与发布原件一致；如仍无法解压，请排查解压器或可用存储空间。');return 0
if __name__=='__main__':raise SystemExit(main())
