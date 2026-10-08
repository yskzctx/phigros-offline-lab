// Local chart plans only; this file never builds a network/upload payload.
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
