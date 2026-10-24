#include <stdbool.h>
#include <stdint.h>
#include <string.h>
/* Offsets verified in original 0.8.2 GameScene.update (native SHA pinned).
 * These are ordinary Config fields; never write JudgeInner or its checks. */
static void lab_prepare_frame(void *scene,bool requested,bool tainted){
 if(!scene)return;
 unsigned char *bytes=scene;uint32_t mods;memcpy(&mods,bytes+0x288,sizeof(mods));
 mods=requested?(mods|1u):(mods&~1u);
 if(tainted)mods|=0x40u; /* Original NO_SHADER/UNRATED: no synthetic local record. */
 memcpy(bytes+0x288,&mods,sizeof(mods));
 bytes[0x2b7]=1; /* Original offlineMode: original ending skips record encoding. */
}
