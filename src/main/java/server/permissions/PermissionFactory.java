package server.permissions;

import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import server.controllers.FuseSessionController;
import server.entities.dto.User;
import server.entities.dto.organization.Organization;
import server.entities.dto.project.Project;
import server.entities.dto.team.Team;
import server.repositories.OrganizationMemberRepository;
import server.repositories.ProjectMemberRepository;
import server.repositories.TeamMemberRepository;

@Service
@Transactional
public class PermissionFactory {

  @Autowired
  private FuseSessionController fuseSessionController;

  @Autowired
  private SessionFactory sessionFactory;

  @Autowired
  private TeamMemberRepository teamMemberRepository;

  @Autowired
  private ProjectMemberRepository projectMemberRepository;

  @Autowired
  private OrganizationMemberRepository organizationMemberRepository;

  public UserPermission createUserPermission(User user) {
    UserPermission permission = new UserPermission(user);
    permission.setFuseSessionController(fuseSessionController);
    return permission;
  }

  public UserToOrganizationPermission createUserToOrganizationPermission(User user, Organization organization) {
    UserToOrganizationPermission permission = new UserToOrganizationPermission(user, organization);
    permission.setSession(sessionFactory.getCurrentSession());
    permission.setRepository(organizationMemberRepository);
    return permission;
  }

  public UserToProjectPermission createUserToProjectPermission(User user, Project project) {
    UserToProjectPermission permission = new UserToProjectPermission(user, project);
    permission.setSession(sessionFactory.getCurrentSession());
    permission.setRepository(projectMemberRepository);
    return permission;
  }

  public UserToTeamPermission createUserToTeamPermission(User user, Team team) {
    UserToTeamPermission permission = new UserToTeamPermission(user, team);
    permission.setSession(sessionFactory.getCurrentSession());
    permission.setRepository(teamMemberRepository);
    return permission;
  }
}
