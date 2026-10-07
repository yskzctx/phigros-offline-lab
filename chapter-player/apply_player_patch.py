from pathlib import Path
import sys,shutil
source=Path(sys.argv[1]).resolve();payload=Path(__file__).resolve().parent
entry=source/'phira/src/lib.rs';text=entry.read_text(encoding='utf-8')
assert 'pub extern "C" fn quad_main()' in text and 'async fn the_main()' in text
assert 'pub mod local_chapter;' not in text
shutil.copy2(payload/'local_chapter.rs',source/'phira/src/local_chapter.rs')
text='pub mod local_chapter;\n'+text
needle='if let Err(err) = the_main().await {'
assert text.count(needle)==1
text=text.replace(needle,'let result = if local_chapter::requested() { local_chapter::run().await } else { the_main().await };\n        if let Err(err) = result {',1)
entry.write_text(text,encoding='utf-8')
print('Added offline chapter entry without modifying Phira online verification or judge code')
