from pathlib import Path
import sys,shutil
source=Path(sys.argv[1]).resolve();payload=Path(__file__).resolve().parent
entry=source/'phira/src/lib.rs';text=entry.read_text(encoding='utf-8')
assert 'pub extern "C" fn quad_main()' in text and 'async fn the_main()' in text
assert 'pub mod local_chapter;' not in text
shutil.copy2(payload/'local_chapter.rs',source/'phira/src/local_chapter.rs')
(source/'phira/src/local_chapter').mkdir(exist_ok=True)
shutil.copy2(payload/'path_scope.rs',source/'phira/src/local_chapter/path_scope.rs')
text='pub mod local_chapter;\n'+text
needle='if let Err(err) = the_main().await {'
assert text.count(needle)==1
text=text.replace(needle,'let result = if local_chapter::requested() { local_chapter::run().await } else { the_main().await };\n        if let Err(err) = result {',1)
entry.write_text(text,encoding='utf-8')
prpr=source/'prpr/src'
shutil.copy2(payload/'runtime_controls.rs',prpr/'chapter_controls.rs')
(prpr/'chapter_controls').mkdir(exist_ok=True)
shutil.copy2(payload/'planner.rs',prpr/'chapter_controls/planner.rs')
lib=prpr/'lib.rs';lib.write_text('pub mod chapter_controls;\n'+lib.read_text(encoding='utf-8'),encoding='utf-8')
judge=prpr/'judge.rs';code=judge.read_text(encoding='utf-8')
needle='pub struct Judge {';assert code.count(needle)==1
code=code.replace(needle,needle+'\n    pub(crate) local_plan: Option<Vec<Vec<u8>>>,',1)
needle='            inner: JudgeInner::new(';assert code.count(needle)==1
code=code.replace(needle,'            local_plan: None,\n'+needle,1)
needle='            self.auto_play_update(res, chart);';assert code.count(needle)==1
code=code.replace(needle,'            if self.local_plan.is_some(){self.local_auto_update(res,chart,bad_notes);}else{self.auto_play_update(res,chart);}',1)
needle='    fn auto_play_update(';assert code.count(needle)==1
code=code.replace(needle,(payload/'local_auto_method.rs').read_text(encoding='utf-8')+'\n'+needle,1)
judge.write_text(code,encoding='utf-8')
game=prpr/'scene/game.rs';code=game.read_text(encoding='utf-8')
needle='let judge = Judge::new(&chart);';assert code.count(needle)==1
code=code.replace(needle,'let mut judge = Judge::new(&chart);\n        judge.local_plan = crate::chapter_controls::for_chart(&chart)?;',1)
game.write_text(code,encoding='utf-8')
print('Added opt-in local chapter skins and actual judgment plans; upstream manual/online paths remain unconfigured')
