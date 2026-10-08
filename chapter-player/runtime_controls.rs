//! Opt-in local chapter controls. Network clients and online records are not used.
use crate::core::{Chart,NoteKind};
use anyhow::{bail,Result};
use std::sync::Mutex;
pub mod planner;
static CONFIG:Mutex<Option<planner::Controls>>=Mutex::new(None);
pub fn configure(config:Option<planner::Controls>){*CONFIG.lock().unwrap()=config;}
pub fn for_chart(chart:&Chart)->Result<Option<Vec<Vec<u8>>>>{
 let guard=CONFIG.lock().unwrap();let Some(config)=guard.as_ref()else{return Ok(None)};
 let mut notes=Vec::new();
 for(line_id,line)in chart.lines.iter().enumerate(){for(id,note)in line.notes.iter().enumerate(){if !note.fake{
  if !note.time.is_finite(){bail!("invalid note time");}
  let kind=match note.kind{NoteKind::Click=>1,NoteKind::Drag=>2,NoteKind::Hold{..}=>3,NoteKind::Flick=>4};notes.push((note.time,line_id,id,kind));
 }}}
 if notes.len()>20000{bail!("local judgment controls support at most 20000 real notes");}
 notes.sort_by(|a,b|a.0.total_cmp(&b.0).then(a.1.cmp(&b.1)).then(a.2.cmp(&b.2)));
 let seed=std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().subsec_nanos();
 let values=planner::plan(config,&notes.iter().map(|n|n.3).collect::<Vec<_>>(),seed);
 let mut result=chart.lines.iter().map(|l|vec![0;l.notes.len()]).collect::<Vec<_>>();
 for(index,(_,line,note,_))in notes.into_iter().enumerate(){result[line][note]=values[index];}Ok(Some(result))
}
