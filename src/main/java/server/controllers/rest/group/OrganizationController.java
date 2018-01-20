package server.controllers.rest.group;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import server.controllers.rest.response.GeneralResponse;
import server.entities.dto.User;
import server.entities.dto.group.GroupApplicant;
import server.entities.dto.group.GroupInvitation;
import server.entities.dto.group.GroupProfile;
import server.entities.dto.group.organization.Organization;
import server.entities.dto.group.organization.OrganizationApplicant;
import server.entities.dto.group.organization.OrganizationInvitation;
import server.entities.dto.group.organization.OrganizationMember;
import server.entities.user_to_group.permissions.PermissionFactory;
import server.entities.user_to_group.permissions.UserToGroupPermission;
import server.entities.user_to_group.relationships.RelationshipFactory;
import server.repositories.group.GroupApplicantRepository;
import server.repositories.group.GroupMemberRepository;
import server.repositories.group.GroupRepository;
import server.repositories.group.organization.OrganizationApplicantRepository;
import server.repositories.group.organization.OrganizationInvitationRepository;
import server.repositories.group.organization.OrganizationMemberRepository;
import server.repositories.group.organization.OrganizationProfileRepository;
import server.repositories.group.organization.OrganizationRepository;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Controller
@RequestMapping(value = "/organizations")
@Api("Organizations")
@Transactional
@SuppressWarnings("unused")
public class OrganizationController extends GroupController<Organization, OrganizationMember> {

  @Autowired
  private PermissionFactory permissionFactory;

  @Autowired
  private OrganizationRepository organizationRepository;

  @Autowired
  private OrganizationApplicantRepository organizationApplicantRepository;

  @Autowired
  OrganizationProfileRepository organizationProfileRepository;

  @Autowired
  private OrganizationMemberRepository organizationMemberRepository;

  @Autowired
  private OrganizationInvitationRepository organizationInvitationRepository;

  @Autowired
  private SessionFactory sessionFactory;

  @Autowired
  private RelationshipFactory relationshipFactory;

  @Override
  protected Organization createGroup() {
    return new Organization();
  }

  @Override
  protected GroupRepository<Organization> getGroupRepository() {
    return organizationRepository;
  }

  @Override
  protected GroupApplicantRepository getGroupApplicantRepository() {
    return organizationApplicantRepository;
  }

  @Override
  protected GroupProfile<Organization> saveProfile(Organization org) {
    return organizationProfileRepository.save(org.getProfile());
  }

  @Override
  protected GroupMemberRepository<Organization, OrganizationMember> getRelationshipRepository() {
    return organizationMemberRepository;
  }

  @Override
  protected UserToGroupPermission getUserToGroupPermission(User user, Organization group) {
    return permissionFactory.createUserToOrganizationPermission(user, group);
  }

  @Override
  protected void removeRelationship(User user, Organization group, int role) {
    relationshipFactory.createUserToOrganizationRelationship(user, group).removeRelationship(role);
  }

  @Override
  protected void addRelationship(User user, Organization group, int role) {
    relationshipFactory.createUserToOrganizationRelationship(user, group).addRelationship(role);
  }

  @Override
  protected GroupApplicant<Organization> getAppliction() {
    return new OrganizationApplicant();
  }

  @Override
  protected GroupInvitation<Organization> getInvitation() {
    return new OrganizationInvitation();
  }

  @Override
  protected void saveInvitation(GroupInvitation<Organization> invitation) {
    organizationInvitationRepository.save((OrganizationInvitation) invitation);
  }

}
