package com.splitsmart.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    public NotificationService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Item(Long id,String type,String title,LocalDateTime createdAt,boolean read,String href) {}
    public record Page(List<Item> items,long unreadCount,Long nextBefore) {}
    private static final String AUTHORIZED="recipient_id=? AND (group_id IS NULL OR EXISTS (SELECT 1 FROM group_members m WHERE m.group_id=notifications.group_id AND m.user_id=notifications.recipient_id))";

    @EventListener
    public void activity(ActivityEvent event) {
        // Synchronous listener participates in the originating transaction: no ghost notifications.
        if("INVITATION".equals(event.type())) {
            invite(event.resourceId()); return;
        }
        if("EMAIL_VERIFIED".equals(event.type())) {
            var pending=jdbc.queryForList("SELECT i.id FROM group_invitations i JOIN users u ON u.email=i.email JOIN expense_groups g ON g.id=i.group_id WHERE u.id=? AND u.email_verified_at IS NOT NULL AND i.status='PENDING' AND i.expires_at>CURRENT_TIMESTAMP AND g.archived=false",Long.class,event.actorId());
            pending.forEach(this::invite); return;
        }
        String title=switch(event.type()) {
            case "EXPENSE" -> "A new shared expense was added";
            case "SETTLEMENT" -> "A repayment was recorded";
            case "MEMBER_JOINED" -> "A member joined your group";
            case "MEMBER_REMOVED" -> "Group membership changed";
            default -> throw new IllegalArgumentException("Unsupported activity event.");
        };
        String type=event.type().startsWith("MEMBER_")?"MEMBERSHIP":event.type();
        var recipients=jdbc.queryForList("SELECT user_id FROM group_members WHERE group_id=? AND user_id<>?",Long.class,event.groupId(),event.actorId());
        for(Long recipient:recipients) insert(recipient,event.groupId(),event.key(),type,title,event.resourceId());
        if("MEMBER_REMOVED".equals(event.type())&&!event.actorId().equals(event.resourceId())) {
            insert(event.resourceId(),null,event.key(),"MEMBERSHIP","Your group membership changed",null);
        }
    }
    private void invite(Long id) {
        var recipients=jdbc.queryForList("SELECT u.id FROM users u JOIN group_invitations i ON i.email=u.email JOIN expense_groups g ON g.id=i.group_id WHERE i.id=? AND i.status='PENDING' AND i.expires_at>CURRENT_TIMESTAMP AND g.archived=false AND u.email_verified_at IS NOT NULL",Long.class,id);
        for(Long recipient:recipients) insert(recipient,null,"invitation:"+id,"INVITATION","You have a group invitation",id);
    }
    private void insert(Long user,Long group,String key,String type,String title,Long resource) {
        jdbc.update("INSERT INTO notifications(recipient_id,group_id,event_key,type,title,resource_id,created_at) SELECT ?,?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM notifications WHERE recipient_id=? AND event_key=?)",
                user,group,key,type,title,resource,LocalDateTime.now(ZoneOffset.UTC),user,key);
        retain(user);
    }
    private void retain(long user) {
        jdbc.update("DELETE FROM notifications WHERE recipient_id=? AND created_at<?",user,LocalDateTime.now(ZoneOffset.UTC).minusDays(90));
        var boundary=jdbc.queryForList("SELECT id FROM notifications WHERE recipient_id=? ORDER BY id DESC LIMIT 1 OFFSET 199",Long.class,user);
        if(!boundary.isEmpty()) jdbc.update("DELETE FROM notifications WHERE recipient_id=? AND id<?",user,boundary.getFirst());
    }
    @Transactional
    public Page list(String subject,Long before,int limit) {
        long user=Long.parseLong(subject);
        if(limit<1||limit>50||before!=null&&before<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid notification page.");
        retain(user);
        var items=jdbc.query("SELECT id,type,title,created_at,read_at,group_id,resource_id FROM notifications WHERE "+AUTHORIZED+" AND (? IS NULL OR id<?) ORDER BY id DESC LIMIT ?",(row,index)->{
            Long group=row.getObject("group_id",Long.class);
            Long resource=row.getObject("resource_id",Long.class);
            String type=row.getString("type");
            String href=group==null?"/groups":"/groups/"+group;
            if(group!=null&&"EXPENSE".equals(type)) href+="/expenses/"+resource;
            if(group!=null&&"SETTLEMENT".equals(type)) href+="/settlements";
            return new Item(row.getLong("id"),type,row.getString("title"),row.getObject("created_at",LocalDateTime.class),row.getObject("read_at")!=null,href);
        },user,before,before,limit+1);
        boolean more=items.size()>limit;
        var visible=items.stream().limit(limit).toList();
        return new Page(visible,count(user),more?visible.getLast().id():null);
    }
    @Transactional
    public long unread(String subject) { long user=Long.parseLong(subject); retain(user); return count(user); }
    private long count(long user) { Long count=jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE "+AUTHORIZED+" AND read_at IS NULL",Long.class,user); return count==null?0:count; }
    @Transactional
    public void mark(String subject,Long id,boolean read) {
        long user=Long.parseLong(subject);
        if(jdbc.update("UPDATE notifications SET read_at=? WHERE id=? AND "+AUTHORIZED,
                read?LocalDateTime.now(ZoneOffset.UTC):null,id,user)!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification unavailable.");
    }
}
