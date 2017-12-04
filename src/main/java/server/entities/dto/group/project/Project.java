package server.entities.dto.group.project;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import lombok.ToString;
import server.entities.dto.group.Group;
import server.entities.dto.group.GroupProfile;

import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;
import javax.persistence.Table;

@ToString(exclude = "profile")
@Entity
@Table(name = "project")
public class Project extends Group<ProjectProfile> {

  @JoinColumn(name = "id", referencedColumnName = "group_id")
  @OneToOne
  private ProjectSettings projectSettings;

  @JsonManagedReference
  @OneToOne(cascade = CascadeType.ALL)
  @JoinColumn(name = "project_profile_id", referencedColumnName = "id")
  private ProjectProfile profile;

  @Override
  public String getGroupType() {
    return "Project";
  }

  @Override
  public ProjectProfile getProfile() {
    return profile;
  }

  @Override
  public void setProfile(ProjectProfile p) {
    profile.setHeadline(p.getHeadline());
    profile.setSummary(p.getSummary());
  }
}
