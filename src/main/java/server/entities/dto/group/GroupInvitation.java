package server.entities.dto.group;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import server.entities.dto.group.interview.Interview;
import server.entities.dto.user.User;
import server.utility.RolesUtility;

import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.MappedSuperclass;

@Data
@MappedSuperclass
public abstract class GroupInvitation<T extends Group> {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private Long id;

  // TODO: Pick better names than "receiver" and "sender"

  // Applicant
  @ManyToOne
  @JoinColumn(name = "receiver_id", referencedColumnName = "id")
  private User receiver;

  // User that sent the invitation
  @ManyToOne
  @JoinColumn(name = "sender_id", referencedColumnName = "id")
  private User sender;

  @ManyToOne
  @JoinColumn(name = "interview_id", referencedColumnName = "id")
  private Interview interview;

  // TODO: Add status validation in this class

  private String status;

  public static boolean isValidInterviewType(String type) {
    return RolesUtility.getRoleFromInvitationType(type).isPresent();
  }

  private String type;

  @JsonIgnore
  public abstract T getGroup();

  @JsonIgnore
  public abstract GroupApplication getApplicant();

  @JsonIgnore
  public abstract void setApplicant(GroupApplication applicant);

  public abstract void setGroup(T group);
}
