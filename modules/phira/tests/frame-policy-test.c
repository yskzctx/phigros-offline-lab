#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "../native/frame-policy.h"
int main(void){
 unsigned char scene[4096],saved[4096];memset(scene,0xa6,sizeof(scene));
 uint32_t mods=0x22;memcpy(scene+0x288,&mods,4);memcpy(saved,scene,sizeof(scene));
 lab_prepare_frame(scene,false,false);
 assert(scene[0x2b7]==1);memcpy(&mods,scene+0x288,4);assert(mods==0x22);
 for(size_t i=0;i<sizeof(scene);i++)if(i<0x288||i>0x28b)if(i!=0x2b7)assert(scene[i]==saved[i]);
 lab_prepare_frame(scene,true,true);memcpy(&mods,scene+0x288,4);assert(mods==0x63);
 lab_prepare_frame(scene,false,true);memcpy(&mods,scene+0x288,4);assert(mods==0x62);
 assert(!memcmp(scene+0xcc8,saved+0xcc8,0x120));
 lab_prepare_frame(NULL,true,true);
 puts("PASS: original offline mode, autoplay bit, tainted-session unrated flag; protected score memory unchanged");
}
