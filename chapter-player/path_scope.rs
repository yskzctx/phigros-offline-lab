use std::{io,path::{Path,PathBuf}};
pub fn owned_chart(root:&Path,chart:&Path)->io::Result<PathBuf>{
    let root=std::fs::canonicalize(root)?;let chart=std::fs::canonicalize(chart)?;
    if !chart.is_dir()||chart==root||!chart.starts_with(&root){return Err(io::Error::new(io::ErrorKind::PermissionDenied,"chart outside chapter root"));}
    Ok(chart)
}
#[cfg(test)]mod tests{
    use super::*;
    fn fixture()->PathBuf{let p=std::env::temp_dir().join(format!("chapter-scope-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));std::fs::create_dir(&p).unwrap();p}
    #[test]fn local_child(){let root=fixture();let child=root.join("chart");std::fs::create_dir(&child).unwrap();assert_eq!(owned_chart(&root,&child).unwrap(),std::fs::canonicalize(child).unwrap());}
    #[test]fn reject_parent_and_sibling(){let root=fixture();let sibling=fixture();assert!(owned_chart(&root,&sibling).is_err());assert!(owned_chart(&root,&root).is_err());assert!(owned_chart(&root,root.parent().unwrap()).is_err());}
    #[cfg(unix)]#[test]fn reject_symlink_escape(){let root=fixture();let sibling=fixture();let link=root.join("chart");std::os::unix::fs::symlink(&sibling,&link).unwrap();assert!(owned_chart(&root,&link).is_err());}
}
