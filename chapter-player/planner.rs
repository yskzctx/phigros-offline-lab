// Local chart plans only; this file never builds a network/upload payload.
#[derive(Clone,Default)] pub struct Controls {pub mode:i32,pub target:i32,pub accuracy:i32,rules:Vec<Rule>}
#[derive(Clone)] struct Rule {judgment:u8,position:u8,start:usize,end:usize,count:usize,indices:Vec<usize>,kind:u8}
#[derive(Clone,Copy)] pub struct Estimate {pub score:i32,pub accuracy:i32}
fn number(s:&str,low:usize,high:usize)->Result<usize,String>{let v=s.trim().parse::<usize>().map_err(|_|"invalid integer")?;if v<low||v>high{Err("out of range".into())}else{Ok(v)}}
impl Controls {
 pub fn parse(mode:i32,target:i32,accuracy:i32,text:&str)->Result<Self,String>{
  if !(0..=2).contains(&mode)||!(0..=1_000_000).contains(&target)||!(0..=10_000).contains(&accuracy)||text.len()>96000{return Err("invalid controls".into());}
  let mut rules=Vec::new();
  if !text.trim().is_empty(){for row in text.trim().split(';'){
   let p=row.split(':').collect::<Vec<_>>();if p.len()!=6&&p.len()!=7{return Err("invalid rule fields".into());}
   let judgment=number(p[0],1,3)? as u8;let position=number(p[1],1,6)? as u8;let start=number(p[2],1,20000)?;let end=number(p[3],1,20000)?;let count=number(p[4],1,20000)?;
   if position==2&&end<start{return Err("reversed range".into());}
   let mut indices=Vec::new();if p[5].len()>12000{return Err("rule list too long".into());}
   if !p[5].trim().is_empty(){for s in p[5].replace('，',",").split(','){let n=number(s,1,20000)?;if !indices.contains(&n){indices.push(n);}if indices.len()>1024{return Err("too many indices".into());}}}
   if position==3&&indices.is_empty(){return Err("empty index list".into());}
   let kind=if p.len()==7{number(p[6],0,4)? as u8}else{0};rules.push(Rule{judgment,position,start,end,count,indices,kind});if rules.len()>16{return Err("too many rules".into());}
  }}Ok(Self{mode,target,accuracy,rules})
 }
}
fn random(seed:&mut u32)->u32{*seed^=*seed<<13;*seed^=*seed>>17;*seed^=*seed<<5;*seed}
pub fn estimate(p:&[u8])->Estimate{
 if p.is_empty(){return Estimate{score:0,accuracy:0};}
 let(mut points,mut combo,mut max_combo)=(0i64,0i64,0i64);
 for &v in p{match v{0=>{points+=100;combo+=1;},2=>{points+=65;combo+=1;},_=>combo=0}max_combo=max_combo.max(combo);}
 let n=p.len()as i64;Estimate{score:((points*9000+max_combo*100000+n/2)/n)as i32,accuracy:((points*100+n/2)/n)as i32}
}
pub fn plan(c:&Controls,types:&[u8],seed:u32)->Vec<u8>{
 let n=types.len();if n==0||n>20000{return Vec::new();}let mut rng=if seed==0{0x6d2b79f5}else{seed};let mut p=vec![0;n];let mut fixed=vec![false;n];
 for r in &c.rules{
  let eligible=(0..n).filter(|i|!fixed[*i]&&(r.kind==0||types[*i]==r.kind)).collect::<Vec<_>>();
  let chosen=match r.position{
   3=>eligible.into_iter().filter(|i|r.indices.contains(&(i+1))).collect::<Vec<_>>(),
   4=>{let mut left=eligible.len();let mut k=r.count.min(left);let mut a=Vec::new();for i in eligible{if k>0&&random(&mut rng)as usize%left<k{a.push(i);k-=1;}left-=1;}a},
   _=>{let(first,last)=match r.position{1=>(r.start,r.start),2=>(r.start,r.end),5=>(r.start,r.start+r.count-1),_=>(1,n)};eligible.into_iter().filter(|i|i+1>=first&&i+1<=last).collect::<Vec<_>>()}
  };for i in chosen{p[i]=r.judgment;fixed[i]=true;}
 }
 if c.mode==0{return p;}
 let base=p.clone();let mut best=estimate(&p);let target=if c.mode==1{c.target}else{c.accuracy};let value=|e:Estimate|if c.mode==1{e.score}else{e.accuracy};let mut error=(value(best)-target).abs();let mut changes=0;
 let mut free=(0..n).filter(|i|!fixed[*i]).collect::<Vec<_>>();for i in 0..free.len(){let j=i+random(&mut rng)as usize%(free.len()-i);free.swap(i,j);}
 for pass in 0..160{
  let misses=if pass<40{pass}else{(pass-40)*free.len()/119};if misses>free.len(){continue;}
  let mut trial=base.clone();for &i in &free[..misses]{trial[i]=1;}let current=estimate(&trial);
  let raw=if c.mode==1{(current.score-target)as f64*n as f64/315000.}else{(current.accuracy-target)as f64*n as f64/3500.};
  let good_slots=free[misses..].iter().copied().filter(|i|types[*i]==1||types[*i]==3).collect::<Vec<_>>();
  let center=raw.round().max(0.)as usize;
  for offset in -2i32..=2{
   let goods=(center as i32+offset).clamp(0,good_slots.len()as i32)as usize;let mut candidate=trial.clone();for &i in &good_slots[..goods]{candidate[i]=2;}
   let e=estimate(&candidate);let distance=(value(e)-target).abs();let count=misses+goods;
   if distance<error||(distance==error&&count<changes){p=candidate;best=e;error=distance;changes=count;}
  }
 }let _=best;p
}
#[cfg(test)] mod tests {
 use super::*;
 #[test] fn default_is_ap_and_rules_have_priority(){
  let c=Controls::parse(0,1_000_000,10_000,"1:1:100:100:1:;2:2:90:110:1:").unwrap();
  let p=plan(&c,&vec![1;120],123);
  assert_eq!(p[99],1);assert_eq!(p[98],2);assert_eq!(p[0],0);
 }
 #[test] fn filters_and_random_counts(){
  let c=Controls::parse(0,1_000_000,10_000,"1:4:1:1:5::4").unwrap();
  let types=(0..80).map(|i|(i%4+1)as u8).collect::<Vec<_>>();let a=plan(&c,&types,123);let b=plan(&c,&types,456);
  assert_eq!(a.iter().filter(|v|**v==1).count(),5);assert_ne!(a,b);
  assert!(a.iter().enumerate().all(|(i,v)|*v==0||types[i]==4));
 }
 #[test] fn goals_use_judgments_and_respect_flick_drag(){
  let types=(0..1000).map(|i|(i%4+1)as u8).collect::<Vec<_>>();
  for (mode,target,accuracy) in [(1,900_000,10_000),(2,1_000_000,9000)]{
   let p=plan(&Controls::parse(mode,target,accuracy,"").unwrap(),&types,123);let e=estimate(&p);
   assert!(p.iter().any(|v|*v!=0));
   assert!(p.iter().enumerate().all(|(i,v)|*v!=2||types[i]==1||types[i]==3));
   assert!((if mode==1{e.score-target}else{e.accuracy-accuracy}).abs()<=500);
  }
 }
 #[test] fn explicit_rules_override_goals_and_reject_invalid(){
  let c=Controls::parse(2,1_000_000,10000,"1:1:100:100:1:").unwrap();assert_eq!(plan(&c,&vec![3;200],9)[99],1);
  assert!(Controls::parse(2,1_000_000,10001,"").is_err());assert!(Controls::parse(0,1_000_000,10000,"4:6:1:1:1:").is_err());
  assert!(Controls::parse(0,1_000_000,10000,"1:3:1:1:1:").is_err());
 }
}
