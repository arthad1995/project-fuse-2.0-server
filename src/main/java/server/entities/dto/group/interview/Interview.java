package server.entities.dto.group.interview;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import server.entities.dto.User;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

@Data
@Entity
@Table(name = "interview")
public class Interview {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private Long id;

  @Column(name = "start_time")
  private LocalDateTime startDateTime;

  @Column(name = "end_time")
  private LocalDateTime endDateTime;

  @Column(name = "group_type")
  @JsonIgnore
  private String groupType;

  @Column(name = "group_id")
  private Long groupId;

  @Column(name = "available")
  private char availability;

  @Column(name = "cancelled")
  private boolean cancelled;

  @Column(name = "code")
  private String code = "";

  @ManyToOne
  @JoinColumn(name = "user_id", referencedColumnName = "id")
  private User user;

  public void setStart(String dateTime) {
    ZonedDateTime zonedDateTime = ZonedDateTime.parse(dateTime);
    startDateTime = zonedDateTime.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }

  public void setEnd(String dateTime) {
    ZonedDateTime zonedDateTime = ZonedDateTime.parse(dateTime);
    endDateTime = zonedDateTime.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }

  @JsonIgnore
  public LocalDateTime getStartDateTime() {
    return startDateTime;
  }

  @JsonIgnore
  public LocalDateTime getEndDateTime() {
    return endDateTime;
  }

  public String getStart() {
    return startDateTime.toString() + "+00:00";
  }

  public String getEnd() {
    return endDateTime.toString() + "+00:00";
  }

  // Generate a unique, 256 character code
  public void generateCode() {
    code = java.util.UUID.randomUUID().toString();
  }
}
