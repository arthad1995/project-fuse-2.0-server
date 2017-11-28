package server.entities.dto.group.team;

import lombok.Data;
import server.entities.dto.group.GroupProfile;

import javax.persistence.*;

@Data
@Entity
@Table(name = "team_profile")
public class TeamProfile  extends GroupProfile<Team> {

    @ManyToOne
    @JoinColumn(name = "team_id", referencedColumnName = "id")
    private Team team;

    @Override
    @Transient
    public Team getGroup() {
        return team;
    }

    @Override
    @Transient
    public void setGroup(Team group) {
        team = group;
    }
}
