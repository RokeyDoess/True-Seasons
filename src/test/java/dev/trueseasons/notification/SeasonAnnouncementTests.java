package dev.trueseasons.notification;

import dev.trueseasons.api.Season;
import dev.trueseasons.config.Settings;
import dev.trueseasons.state.StateStore;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

public final class SeasonAnnouncementTests {
    private static int checks;
    private static final class Viewer {
        final List<Component> messages=new ArrayList<>();final Player player;
        Viewer(boolean op,boolean admin){
            player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->switch(method.getName()){
                case "isOp"->op;
                case "hasPermission"->{check(args[0].equals("trueseasons.command.admin"),"Only full admin permission grants migration notices");yield admin;}
                case "sendMessage"->{messages.add((Component)args[0]);yield null;}
                default->throw new AssertionError("Notices must not inspect terrain or player location: "+method.getName());
            });
        }
        List<String> text(){return messages.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();}
    }
    public static void main(String[]args)throws Exception{
        Path root=Files.createTempDirectory("trueseasons-announcements-");
        try{
            StateStore state=new StateStore(root);Settings settings=allocate(Settings.class);
            set(settings,"enabled",true);set(settings,"seasonAnnouncements",true);set(settings,"migrationAnnouncements",true);
            Viewer normal=new Viewer(false,false),admin=new Viewer(false,true),op=new Viewer(true,false);
            List<Player> online=List.of(normal.player,admin.player,op.player);
            var notices=notices(state,settings,online);
            notices.resume("autumn");notices.complete("autumn",true,0);
            check(normal.messages.isEmpty()&&admin.messages.isEmpty(),"An existing installation does not invent an earlier transition");
            notices.start(Season.AUTUMN,Season.WINTER,"winter");
            check(normal.text().equals(List.of("Winter has begun.")),"All ordinary players receive just the unprefixed season message");
            List<String> expected=List.of("Winter has begun.","[True Seasons] Winter migration started. Check /ts migration for more details.");
            check(admin.text().equals(expected),"Permission-granted admins receive both start messages");
            check(op.text().equals(expected),"Operators receive admin notices even without admin permission");
            check(contains(normal.messages.getFirst(),"Winter has begun.",NamedTextColor.AQUA),"Public winter text has its season color");
            check(click(admin.messages.get(1)),"The migration command runs on click");
            notices.start(Season.AUTUMN,Season.WINTER,"winter");
            check(admin.messages.size()==2,"Repeated start notifications for a job are suppressed");
            notices.complete("winter",false,0);notices.complete("winter",true,12);
            check(admin.messages.size()==2&&notices.pending("winter"),"Terrain work and pending BlueMap regions both hold completion");
            state=new StateStore(root);notices=notices(state,settings,online);notices.resume("winter");
            check(admin.messages.size()==2&&notices.pending("winter"),"An interrupted migration resumes without another start notice");
            notices.complete("winter",true,0);
            check(admin.text().getLast().equals("[True Seasons] Migration complete.")&&op.text().getLast().equals("[True Seasons] Migration complete."),"Completion goes to both admin audiences");
            check(normal.messages.size()==1,"Ordinary players never receive migration completion");
            check(contains(admin.messages.getLast(),"Migration complete.",NamedTextColor.GREEN),"Successful completion is green");
            notices.complete("winter",true,0);notices.complete("winter",false,3);notices.complete("winter",true,0);
            check(admin.messages.size()==3,"Routine scans, new terrain and repeated completion checks cannot replay a notice");
            state=new StateStore(root);notices=notices(state,settings,online);notices.resume("winter");notices.complete("winter",true,0);
            check(admin.messages.size()==3&&!notices.pending("winter"),"Completed notices survive restart without replay");
            notices.start(Season.WINTER,Season.WINTER,"winter-rules");
            check(normal.messages.size()==1&&admin.messages.size()==4,"A new same-season job notifies admins without announcing another winter");
            notices.start(Season.WINTER,Season.SPRING,"spring");int count=admin.messages.size();
            notices.complete("winter-rules",true,0);
            check(admin.messages.size()==count&&notices.pending("spring"),"A superseded migration cannot complete the new season's notice");
            state.cleanup=true;notices.complete("spring",true,0);notices.cancel();state.cleanup=false;
            notices.complete("spring",true,0);
            check(admin.messages.size()==count&&!notices.pending("spring"),"Cleanup cancels the interrupted season's completion notice");
            set(settings,"seasonAnnouncements",false);notices.start(Season.SPRING,Season.SUMMER,"summer");
            check(normal.messages.size()==2&&admin.messages.size()==count+1,"The public switch can be disabled independently");
            set(settings,"seasonAnnouncements",true);set(settings,"migrationAnnouncements",false);
            notices.start(Season.SUMMER,Season.AUTUMN,"autumn-two");count=admin.messages.size();notices.complete("autumn-two",true,0);
            check(normal.text().getLast().equals("Autumn has begun.")&&admin.messages.size()==count,"Public announcements work with private notices disabled");
            set(settings,"migrationAnnouncements",true);notices.complete("autumn-two",true,0);
            check(admin.messages.size()==count,"Enabling private notices later does not replay an old completion");
            set(settings,"enabled",false);notices.start(Season.AUTUMN,Season.WINTER,"disabled");notices.complete("disabled",true,0);
            check(admin.messages.size()==count&&!notices.pending("disabled"),"Disabled seasonal processing does not announce migration work");
            set(settings,"enabled",true);state.clean=true;notices.start(Season.WINTER,Season.SPRING,"restored");
            check(admin.messages.size()==count&&!notices.pending("restored"),"Restored mode stays silent");
            Files.writeString(root.resolve("jobs.yml"),"schema: 1\n");state=new StateStore(root);
            check(state.announcementVersion.isEmpty(),"Older state files accept the new optional announcement fields");
        }finally{
            Path allowed=Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            if(!root.toRealPath().startsWith(allowed)||root.toRealPath().equals(allowed))throw new IllegalStateException("Unsafe test cleanup");
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
        System.out.println("Season announcement tests passed: "+checks+" assertions");
    }
    private static SeasonAnnouncements notices(StateStore state,Settings settings,List<Player> online){
        return new SeasonAnnouncements(state,()->settings,()->online,()->{try{state.save();}catch(Exception failure){throw new RuntimeException(failure);}});
    }
    private static boolean click(Component component){
        if(component.clickEvent()!=null&&component.clickEvent().equals(ClickEvent.runCommand("/ts migration")))return true;
        return component.children().stream().anyMatch(SeasonAnnouncementTests::click);
    }
    private static boolean contains(Component component,String text,NamedTextColor color){
        if(component instanceof TextComponent leaf&&leaf.content().contains(text)&&color.equals(leaf.color()))return true;
        return component.children().stream().anyMatch(child->contains(child,text,color));
    }
    private static <T>T allocate(Class<T> type)throws Exception{
        Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field instance=unsafe.getDeclaredField("theUnsafe");instance.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance",Class.class).invoke(instance.get(null),type));
    }
    private static void set(Object target,String name,Object value)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
}
