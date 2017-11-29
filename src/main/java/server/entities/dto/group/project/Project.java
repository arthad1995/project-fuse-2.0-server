package server.entities.dto.group.project;

import server.entities.dto.group.Group;

import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.OneToOne;
import javax.persistence.Table;

@Entity
@Table(name = "project")
public class Project extends Group {

  @JoinColumn(name = "id", referencedColumnName = "group_id")
  @OneToOne
  private ProjectSettings projectSettings;

  @Override
  public String getGroupType() {
    return "Project";
  }
}
