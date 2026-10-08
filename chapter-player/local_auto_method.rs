    // Called only when a local chapter installed a plan. Standard upstream
    // autoplay and manual judging remain in their original branches.
    fn local_auto_update(&mut self, res:&mut Resource, chart:&mut Chart, bad_notes:&mut Vec<BadNote>){
        let t=res.time;let spd=res.config.speed as f64;let mut events=Vec::new();
        for(line_id,(line,(idx,st)))in chart.lines.iter_mut().zip(self.notes.iter_mut()).enumerate(){
            for id in &idx[*st..]{
                let note=&mut line.notes[*id as usize];let desired=self.local_plan.as_ref().unwrap()[line_id][*id as usize];
                if let JudgeStatus::Hold(..)=note.judge{
                    if let NoteKind::Hold{end_time,..}=note.kind{if t>=end_time{note.judge=JudgeStatus::Judged;events.push((line_id,*id,desired));}}
                    continue;
                }
                if !matches!(note.judge,JudgeStatus::NotJudged){continue;}
                if note.time>t{break;}
                let delay=match desired{1=>LIMIT_BAD+0.001,2=>(LIMIT_PERFECT+LIMIT_GOOD)*0.5,3=>(LIMIT_GOOD+LIMIT_BAD)*0.5,_=>0.};
                if t<note.time+delay*spd{continue;}
                if matches!(note.kind,NoteKind::Hold{..})&&(desired==0||desired==2){
                    note.hitsound.play(res);let perfect=desired==0;
                    self.judgements.borrow_mut().push((t,line_id as u32,*id,Err(perfect)));
                    note.judge=JudgeStatus::Hold(perfect,t,delay,false,f64::INFINITY);
                }else{note.judge=JudgeStatus::Judged;events.push((line_id,*id,desired));}
            }
            while idx.get(*st).is_some_and(|id|matches!(line.notes[*id as usize].judge,JudgeStatus::Judged)){*st+=1;}
        }
        for(line_id,id,desired)in events{
            let judgement=match desired{1=>Judgement::Miss,2=>Judgement::Good,3=>Judgement::Bad,_=>Judgement::Perfect};
            let diff=match desired{1=>LIMIT_BAD+0.001,2=>(LIMIT_PERFECT+LIMIT_GOOD)*0.5,3=>(LIMIT_GOOD+LIMIT_BAD)*0.5,_=>0.};
            self.commit(t,judgement,line_id as u32,id,diff);
            let line=&mut chart.lines[line_id];let note=&mut line.notes[id as usize];
            line.object.set_time(t);note.object.set_time(t);
            let line=&chart.lines[line_id];let note=&line.notes[id as usize];let transform=line.now_transform(res,&chart.lines);
            if desired==0||desired==2{
                let color=if desired==2{res.res_pack.info.fx_good()}else{res.res_pack.info.fx_perfect()};
                res.with_model(transform*note.object.now(res),|res|res.emit_at_origin(note.rotation(line),note.fx_color.unwrap_or(color)));
                if !matches!(note.kind,NoteKind::Hold{..}){note.hitsound.play(res);}
            }else if desired==3&&!matches!(note.kind,NoteKind::Hold{..}){
                let mut matrix=transform;if !note.above{matrix.append_nonuniform_scaling_mut(&Vector::new(1.,-1.));}
                let incline=line.incline.now_opt().map(|it|it.to_radians().sin()).unwrap_or_default();
                matrix*=note.now_transform(res,&line.ctrl_obj.borrow_mut(),((note.height-line.height.now()as f64)/res.aspect_ratio as f64*note.speed)as f32,incline);
                bad_notes.push(BadNote{time:t,kind:note.kind.clone(),matrix});
            }
        }
        self.last_time=t/spd;
    }
