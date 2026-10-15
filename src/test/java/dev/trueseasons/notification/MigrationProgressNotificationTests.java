package dev.trueseasons.notification;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public final class MigrationProgressNotificationTests {
    private static int checks;
    private static final class Viewer {
        boolean online=true,allowed=true,op;int messages;final Player player;
        Viewer(){player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->switch(method.getName()){
            case "isOnline"->online;case "isOp"->op;
            case "hasPermission"->{check(args[0].equals("trueseasons.command.migration"),"Progress retains the migration permission boundary");yield allowed;}
            case "sendMessage"->{check(args[0] instanceof Component,"Live progress uses native chat components");messages++;yield null;}
            default->throw new AssertionError("Live progress must not read terrain or player location: "+method.getName());
        });}
    }
    public static void main(String[]args){
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();Viewer a=new Viewer(),b=new Viewer();
        Map<UUID,Player> online=new HashMap<>(Map.of(first,a.player,second,b.player));
        AtomicInteger summaries=new AtomicInteger(),lookups=new AtomicInteger();boolean[] complete={false};
        var notices=new MigrationProgressNotifications(id->{lookups.incrementAndGet();return online.get(id);},
            ()->{summaries.incrementAndGet();return Component.text("Migration status");},()->complete[0]);
        for(int tick=0;tick<100;tick++)notices.tick(tick);
        check(summaries.get()==0&&lookups.get()==0,"No subscribers means no player lookup or summary work");
        check(notices.toggle(first,100),"First toggle subscribes");notices.tick(199);
        check(a.messages==0&&lookups.get()==0,"No work runs before the five-second deadline");
        notices.tick(200);check(a.messages==1&&b.messages==0,"Only the subscribing player gets progress");
        check(notices.toggle(second,200),"Another player can subscribe independently");
        notices.tick(299);check(a.messages==1&&b.messages==0,"Repeated ticks cannot send early progress");
        notices.tick(300);check(a.messages==2&&b.messages==1&&summaries.get()==2,"Players due together share one summary");
        check(!notices.toggle(first,301),"The second toggle unsubscribes");notices.tick(400);
        check(a.messages==2&&b.messages==2,"Turning updates off does not affect another player");
        complete[0]=true;notices.tick(500);int rendered=summaries.get();notices.tick(600);
        check(b.messages==3&&summaries.get()==rendered,"Completion sends one final progress row and stops automatically");
        complete[0]=false;notices.toggle(first,600);notices.remove(first);notices.tick(700);
        check(a.messages==2&&summaries.get()==rendered,"Disconnect removal stops updates without persisting a subscription");
        notices.toggle(first,700);a.allowed=false;notices.tick(800);a.allowed=true;notices.tick(900);
        check(a.messages==2,"Losing permission removes the subscription rather than silently resuming later");
        notices.toggle(first,900);a.online=false;notices.tick(1000);a.online=true;notices.tick(1100);
        check(a.messages==2,"Offline players cannot keep an active subscription");
        notices.toggle(first,1100);online.remove(first);notices.tick(1200);online.put(first,a.player);notices.tick(1300);
        check(a.messages==2,"Missing players are forgotten without retaining player objects");
        a.allowed=false;a.op=true;notices.toggle(first,1300);notices.tick(1400);
        check(a.messages==3,"Operators retain progress access without a granted permission");notices.remove(first);
        int start=Integer.MAX_VALUE-50;notices.toggle(first,start);notices.tick(Integer.MAX_VALUE);
        check(a.messages==3,"Tick counter rollover does not cause premature updates");notices.tick(start+100);
        check(a.messages==4,"The five-second deadline remains valid across tick rollover");
        notices.remove(first);notices.toggle(first,10);notices.toggle(second,50);notices.tick(110);
        check(a.messages==5&&b.messages==3,"Staggered subscribers retain their own deadlines");notices.tick(149);
        check(b.messages==3,"An unrelated subscriber cannot advance another player's deadline");notices.tick(150);
        check(b.messages==4,"Staggered deadlines receive updates when due");
        System.out.println("Migration progress notification tests passed: "+checks+" assertions");
    }
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
}
