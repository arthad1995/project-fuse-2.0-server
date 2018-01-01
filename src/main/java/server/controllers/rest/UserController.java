package server.controllers.rest;

import static server.constants.Availability.NOT_AVAILABLE;
import static server.constants.InvitationStatus.ACCEPTED;
import static server.constants.InvitationStatus.PENDING;
import static server.constants.RegistrationStatus.REGISTERED;
import static server.constants.RegistrationStatus.UNREGISTERED;
import static server.constants.RoleValue.DEFAULT_USER;
import static server.constants.RoleValue.INVITED_TO_INTERVIEW;
import static server.constants.RoleValue.INVITED_TO_JOIN;
import static server.constants.RoleValue.TO_INTERVIEW;
import static server.controllers.rest.response.CannedResponse.INSUFFICIENT_PRIVELAGES;
import static server.controllers.rest.response.CannedResponse.INVALID_FIELDS;
import static server.controllers.rest.response.CannedResponse.INVALID_REGISTRATION_KEY;
import static server.controllers.rest.response.CannedResponse.INVALID_SESSION;
import static server.controllers.rest.response.CannedResponse.NO_GROUP_FOUND;
import static server.controllers.rest.response.CannedResponse.NO_INVITATION_FOUND;
import static server.controllers.rest.response.CannedResponse.NO_USER_FOUND;
import static server.controllers.rest.response.GeneralResponse.Status.BAD_DATA;
import static server.controllers.rest.response.GeneralResponse.Status.DENIED;
import static server.controllers.rest.response.GeneralResponse.Status.OK;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.util.AlternativeJdkIdGenerator;
import org.springframework.util.IdGenerator;
import org.springframework.web.bind.annotation.*;
import server.controllers.FuseSessionController;
import server.controllers.MembersOfGroupController;
import server.controllers.rest.response.GeneralResponse;
import server.controllers.rest.response.GeneralResponse.Status;
import server.email.StandardEmailSender;
import server.entities.PossibleError;
import server.entities.dto.FuseSession;
import server.entities.dto.UnregisteredUser;
import server.entities.dto.User;
import server.entities.dto.UserProfile;
import server.entities.dto.group.GroupApplicant;
import server.entities.dto.group.GroupInvitation;
import server.entities.dto.group.interview.Interview;
import server.entities.dto.group.organization.Organization;
import server.entities.dto.group.organization.OrganizationApplicant;
import server.entities.dto.group.organization.OrganizationInvitation;
import server.entities.dto.group.project.Project;
import server.entities.dto.group.project.ProjectApplicant;
import server.entities.dto.group.project.ProjectInvitation;
import server.entities.dto.group.team.Team;
import server.entities.dto.group.team.TeamApplicant;
import server.entities.dto.group.team.TeamInvitation;
import server.entities.user_to_group.permissions.PermissionFactory;
import server.entities.user_to_group.permissions.UserPermission;
import server.entities.user_to_group.permissions.UserToGroupPermission;
import server.entities.user_to_group.permissions.UserToOrganizationPermission;
import server.entities.user_to_group.permissions.UserToProjectPermission;
import server.entities.user_to_group.permissions.UserToTeamPermission;
import server.entities.user_to_group.permissions.results.JoinResult;
import server.entities.user_to_group.relationships.RelationshipFactory;
import server.entities.user_to_group.relationships.UserToGroupRelationship;
import server.entities.user_to_group.relationships.UserToOrganizationRelationship;
import server.entities.user_to_group.relationships.UserToProjectRelationship;
import server.entities.user_to_group.relationships.UserToTeamRelationship;
import server.repositories.UnregisteredUserRepository;
import server.repositories.UserProfileRepository;
import server.repositories.UserRepository;
import server.repositories.group.InterviewRepository;
import server.repositories.group.organization.OrganizationApplicantRepository;
import server.repositories.group.organization.OrganizationInvitationRepository;
import server.repositories.group.organization.OrganizationRepository;
import server.repositories.group.project.ProjectApplicantRepository;
import server.repositories.group.project.ProjectInvitationRepository;
import server.repositories.group.project.ProjectRepository;
import server.repositories.group.team.TeamApplicantRepository;
import server.repositories.group.team.TeamInvitationRepository;
import server.repositories.group.team.TeamRepository;
import server.utility.RolesUtility;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping(value = "/users")
@SuppressWarnings("unused")
public class UserController {

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private FuseSessionController fuseSessionController;

  @Autowired
  private PermissionFactory permissionFactory;

  @Autowired
  private TeamInvitationRepository teamInvitationRepository;

  @Autowired
  private TeamApplicantRepository teamApplicantRepository;

  @Autowired
  private TeamRepository teamRepository;

  @Autowired
  private ProjectApplicantRepository projectApplicantRepository;

  @Autowired
  private ProjectInvitationRepository projectInvitationRepository;

  @Autowired
  private ProjectRepository projectRepository;

  @Autowired
  private UserProfileRepository userProfileRepository;

  @Autowired
  private OrganizationInvitationRepository organizationInvitationRepository;

  @Autowired
  private OrganizationRepository organizationRepository;

  @Autowired
  private OrganizationApplicantRepository organizationApplicantRepository;

  @Autowired
  private UnregisteredUserRepository unregisteredUserRepository;

  @Autowired
  private InterviewRepository interviewRepository;

  @Autowired
  private RelationshipFactory relationshipFactory;

  @Autowired
  private MembersOfGroupController membersOfGroupController;

  @Value("${fuse.requireRegistration}")
  private boolean requireRegistration;

  @Autowired
  private StandardEmailSender emailSender;

  private static IdGenerator generator = new AlternativeJdkIdGenerator();


  @PostMapping
  @ResponseBody
  public GeneralResponse addNewUser(@RequestBody User user, HttpServletRequest request, HttpServletResponse response) {

    List<String> errors = new ArrayList<>();

    if (user != null) {
      if (user.getName() == null)
        errors.add("Missing Name");
      if (user.getEncoded_password() == null)
        errors.add("Missing Password");
      if (user.getEmail() == null)
        errors.add("Missing Email");
      if (errors.size() == 0 && userRepository.findByEmail(user.getEmail()) != null)
        errors.add("Username already exists!");
    } else {
      errors.add("No request body found");
    }

    if (errors.size() != 0) {
      return new GeneralResponse(response, errors);
    }

    assert user != null;

    if (requireRegistration) {
      user.setRegistrationStatus(UNREGISTERED);
    } else {
      user.setRegistrationStatus(REGISTERED);
    }

    User savedUser = userRepository.save(user);
    Long id = savedUser.getId();

    if (requireRegistration) {
      String registrationKey = generator.generateId().toString();

      UnregisteredUser unregisteredUser = new UnregisteredUser();
      unregisteredUser.setUserId(id);
      unregisteredUser.setRegistrationKey(registrationKey);

      unregisteredUserRepository.save(unregisteredUser);

      emailSender.sendRegistrationEmail(user.getEmail(), registrationKey);
    }

    return new GeneralResponse(response, OK, errors, savedUser);
  }

  @PostMapping (path = "/apply/team/{id}")
  @ResponseBody
  public GeneralResponse applyTeam(@PathVariable(value = "id") Long id,@RequestBody User user, @RequestBody TeamApplicant applicant, HttpServletRequest request, HttpServletResponse response) {

    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, Status.DENIED, errors);
    }
    applicant.setSender(user);
    applicant.setStatus(PENDING);
    //applicant.convert(applicant.getTime().atZone());
    Team team = teamRepository.findOne(id);
    if(team==null){
      errors.add(NO_GROUP_FOUND);
      return new GeneralResponse(response, Status.DENIED, errors);
    }

    applicant.setTeam(team);
    teamApplicantRepository.save(applicant);
  return  new GeneralResponse(response, Status.OK, errors);
  }

  @PostMapping (path = "/apply/project/{id}")
  @ResponseBody
  public GeneralResponse applyProject(@PathVariable(value = "id") Long id, @RequestBody User user, @RequestBody ProjectApplicant applicant, HttpServletRequest request, HttpServletResponse response) {

    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, Status.DENIED, errors);
    }
    applicant.setSender(user);
    applicant.setStatus(PENDING);
    Project project = projectRepository.findOne(id);
    if(project==null){
      errors.add(NO_GROUP_FOUND);
      return new GeneralResponse(response, Status.DENIED, errors);
    }

    applicant.setProject(project);
    projectApplicantRepository.save(applicant);
    return  new GeneralResponse(response, Status.OK, errors);
  }

  @PostMapping (path = "/apply/organization/{id}")
  @ResponseBody
  public GeneralResponse applyOrganization(@PathVariable(value = "id") Long id, @RequestBody User user, @RequestBody OrganizationApplicant applicant, HttpServletRequest request, HttpServletResponse response) {

    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, Status.DENIED, errors);
    }
    applicant.setSender(user);
    applicant.setStatus(PENDING);
    Organization organization = organizationRepository.findOne(id);
    if(organization==null){
      errors.add(NO_GROUP_FOUND);
      return new GeneralResponse(response, Status.DENIED, errors);
    }

    applicant.setOrganization(organization);
    organizationApplicantRepository.save(applicant);
    return  new GeneralResponse(response, Status.OK, errors);
  }

  @PostMapping(path = "/login")
  @ResponseBody
  public GeneralResponse login(@RequestBody User user, HttpServletRequest request, HttpServletResponse response) {

    logoutIfLoggedIn(user, request);

    List<String> errors = new ArrayList<>();
    if (user == null) {
      errors.add("Invalid Credentials");
    } else {
      User dbUser = userRepository.findByEmail(user.getEmail());

      if (dbUser == null) {
        errors.add("Invalid Credentials");
      } else {
        user.setEncoded_password(dbUser.getEncoded_password());

        if (user.checkPassword()) {
          return new GeneralResponse(response, OK, null, fuseSessionController.createSession(dbUser));
        }
        errors.add("Invalid Credentials");
      }
    }

    return new GeneralResponse(response, Status.DENIED, errors);
  }

  @PostMapping(path = "/logout")
  @ResponseBody
  public GeneralResponse logout(HttpServletRequest request, HttpServletResponse response) {
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (session.isPresent()) {
      fuseSessionController.deleteSession(session.get());
      return new GeneralResponse(response, OK);
    } else {
      List<String> errors = new ArrayList<>();
      errors.add("No active session");
      return new GeneralResponse(response, Status.ERROR, errors);
    }
  }

  @GetMapping(path = "/{id}")
  @ResponseBody
  public GeneralResponse getUserbyID(@PathVariable(value = "id") Long id, HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, Status.DENIED, errors);
    }

    if (id == null) {
      errors.add(INVALID_FIELDS);
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    User byId = userRepository.findOne(id);
    if (byId == null) {
      errors.add(NO_USER_FOUND);
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    return new GeneralResponse(response, OK, null, byId);
  }

  @GetMapping(path = "/get_by_email/{email}")
  @ResponseBody
  public GeneralResponse getUserbyEmail(@PathVariable(value = "email") String email, HttpServletRequest request, HttpServletResponse response) {

    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, Status.DENIED, errors);
    }

    if (email == null) {
      errors.add(INVALID_FIELDS);
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    User byEmail = userRepository.findByEmail(email);
    if (byEmail == null) {
      errors.add(NO_USER_FOUND);
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    return new GeneralResponse(response, OK, null, byEmail);
  }

  @CrossOrigin
  @PutMapping(path = "/{id}")
  @ResponseBody
  public GeneralResponse updateCurrentUser(@PathVariable long id, @RequestBody User userData, HttpServletRequest request, HttpServletResponse response) {
    //to Use profile for profile
    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, Status.DENIED, errors);
    }

    User userToSave = session.get().getUser();

    if(id != userToSave.getId()){
      // have this take care of misc updates by admins/moderators (e.g. flag user or revoke access)

      errors.add("Unable to edit user, permission denied");
      return new GeneralResponse(response, Status.DENIED, errors);
    }

    // Merging instead of direct copying ensures we're very clear about what can be edited, and it provides easy checks

    if (userData.getName() != null)
      userToSave.setName(userData.getName());

    if (userData.getEncoded_password() != null)
      userToSave.setEncoded_password(userData.getEncoded_password());

    if (userData.getProfile() != null) {
      if (userToSave.getProfile() == null) {
        userData.getProfile().setUser(userToSave);
        UserProfile profile = userProfileRepository.save(userData.getProfile());
        userToSave.setProfile(profile);
      } else {
        userToSave.setProfile(userToSave.getProfile().merge(userToSave.getProfile(), userData.getProfile()));
      }
    }
    userRepository.save(userToSave);
    return new GeneralResponse(response, Status.OK);
  }

  @GetMapping
  @ResponseBody
  public GeneralResponse getAllUsers(HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();
    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, Status.DENIED, errors);
    }
    return new GeneralResponse(response, OK, null, userRepository.findAll());
  }

  @GetMapping(path = "/joined/teams")
  @ResponseBody
  public GeneralResponse getAllTeamsOfUser(HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, Status.DENIED, errors);
    }
    User user = session.get().getUser();

    return new GeneralResponse(response, OK, null, membersOfGroupController.getTeamsUserIsPartOf(user));
  }

  @GetMapping(path = "/joined/organizations")
  @ResponseBody
  public GeneralResponse getAllOrganizationsOfUser(HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, Status.DENIED, errors);
    }
    User user = session.get().getUser();

    return new GeneralResponse(response, OK, null, membersOfGroupController.getOrganizationsUserIsPartOf(user));
  }


  @GetMapping(path = "/joined/projects")
  @ResponseBody
  public GeneralResponse getAllProjectsOfUser(HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, Status.DENIED, errors);
    }
    User user = session.get().getUser();

    return new GeneralResponse(response, OK, null, membersOfGroupController.getProjectsUserIsPartOf(user));
  }


  @GetMapping(path = "/register/{registrationKey}")
  @ResponseBody
  public GeneralResponse register(@PathVariable(value = "registrationKey") String registrationKey, HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, DENIED, errors);
    }

    User user = session.get().getUser();

    UnregisteredUser unregisteredUser = unregisteredUserRepository.findOne(user.getId());

    if (unregisteredUser == null) {
      errors.add(NO_USER_FOUND);
      return new GeneralResponse(response, errors);
    }

    if (!unregisteredUser.getRegistrationKey().equals(registrationKey)) {
      errors.add(INVALID_REGISTRATION_KEY);
      return new GeneralResponse(response, errors);
    }

    user.setRegistrationStatus(REGISTERED);
    userRepository.save(user);

    unregisteredUserRepository.delete(unregisteredUser);

    return new GeneralResponse(response, OK, null,
        projectInvitationRepository.findByReceiver(user));
  }

  @GetMapping(path = "/incoming/invites/project")
  @ResponseBody
  public GeneralResponse getProjectInvites(HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, DENIED, errors);
    }

    User user = session.get().getUser();

    return new GeneralResponse(response, OK, null,
        projectInvitationRepository.findByReceiver(user));
  }

  @GetMapping(path = "/incoming/invites/organization")
  @ResponseBody
  public GeneralResponse getOrganizationInvites(HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, DENIED, errors);
    }

    User user = session.get().getUser();

    return new GeneralResponse(response, OK, null,
        organizationInvitationRepository.findByReceiver(user));
  }


  @GetMapping(path = "/incoming/invites/team")
  @ResponseBody
  public GeneralResponse getTeamInvites(HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, DENIED, errors);
    }

    User user = session.get().getUser();

    return new GeneralResponse(response, OK, null,
        teamInvitationRepository.findByReceiver(user));
  }

  @PostMapping(path = "/accept/invite/team")
  @ResponseBody
  public GeneralResponse acceptTeamInvite(@RequestBody TeamInvitation teamInvitation, HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, DENIED, errors);
    }

    TeamInvitation savedInvitation = teamInvitationRepository.findOne(teamInvitation.getId());
    if (savedInvitation == null) {
      errors.add(NO_INVITATION_FOUND);
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    User user = session.get().getUser();
    if (!user.getId().equals(savedInvitation.getReceiver().getId())) {
      errors.add(INSUFFICIENT_PRIVELAGES);
      return new GeneralResponse(response, DENIED, errors);
    }

    Team group = savedInvitation.getGroup();
    UserToTeamPermission permission = permissionFactory.createUserToTeamPermission(user, group);

    UserToTeamRelationship userToTeamRelationship = relationshipFactory.createUserToTeamRelationship(user, group);
    PossibleError possibleError = addRelationshipsIfNotError(savedInvitation, permission, userToTeamRelationship);

    if (!possibleError.hasError()) {
      savedInvitation.setStatus(ACCEPTED);
      teamInvitationRepository.save(savedInvitation);
    }
    return new GeneralResponse(response, possibleError.getStatus(), possibleError.getErrors());
  }

  @PostMapping(path = "/accept/invite/project")
  @ResponseBody
  public GeneralResponse acceptProjectInvite(@RequestBody ProjectInvitation projectInvitation, HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, DENIED, errors);
    }

    ProjectInvitation savedInvitation = projectInvitationRepository.findOne(projectInvitation.getId());
    if (savedInvitation == null) {
      errors.add(NO_INVITATION_FOUND);
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    User user = session.get().getUser();
    if (!user.getId().equals(savedInvitation.getReceiver().getId())) {
      errors.add(INSUFFICIENT_PRIVELAGES);
      return new GeneralResponse(response, DENIED, errors);
    }

    Project group = savedInvitation.getGroup();
    UserToProjectPermission permission = permissionFactory.createUserToProjectPermission(user, group);

    UserToProjectRelationship userToTeamRelationship = relationshipFactory.createUserToProjectRelationship(user, group);
    PossibleError possibleError = addRelationshipsIfNotError(savedInvitation, permission, userToTeamRelationship);

    if (!possibleError.hasError()) {
      savedInvitation.setStatus(ACCEPTED);
      projectInvitationRepository.save(savedInvitation);
    }
    return new GeneralResponse(response, possibleError.getStatus(), possibleError.getErrors());
  }


  @PostMapping(path = "/accept/invite/organization")
  @ResponseBody
  public GeneralResponse acceptOrganizationInvite(@RequestBody OrganizationInvitation organizationInvitation, HttpServletRequest request, HttpServletResponse response) {
    List<String> errors = new ArrayList<>();

    Optional<FuseSession> session = fuseSessionController.getSession(request);
    if (!session.isPresent()) {
      errors.add(INVALID_SESSION);
      return new GeneralResponse(response, DENIED, errors);
    }

    OrganizationInvitation savedInvitation = organizationInvitationRepository.findOne(organizationInvitation.getId());
    if (savedInvitation == null) {
      errors.add(NO_INVITATION_FOUND);
      return new GeneralResponse(response, BAD_DATA, errors);
    }

    User user = session.get().getUser();
    if (!user.getId().equals(savedInvitation.getReceiver().getId())) {
      errors.add(INSUFFICIENT_PRIVELAGES);
      return new GeneralResponse(response, DENIED, errors);
    }

    Organization group = savedInvitation.getGroup();
    UserToOrganizationPermission permission = permissionFactory.createUserToOrganizationPermission(user, group);

    UserToOrganizationRelationship userToTeamRelationship = relationshipFactory.createUserToOrganizationRelationship(user, group);
    PossibleError possibleError = addRelationshipsIfNotError(savedInvitation, permission, userToTeamRelationship);

    if (!possibleError.hasError()) {
      savedInvitation.setStatus(ACCEPTED);
      organizationInvitationRepository.save(savedInvitation);
    }
    return new GeneralResponse(response, possibleError.getStatus(), possibleError.getErrors());
  }


  private PossibleError addRelationshipsIfNotError(GroupInvitation invitation, UserToGroupPermission permission,
                                                   UserToGroupRelationship relationship) {

    List<String> errors = new ArrayList<>();

    User user = invitation.getReceiver();
    Optional<Integer> roleFromInvitationType = RolesUtility.getRoleFromInvitationType(invitation.getType());
    if (!roleFromInvitationType.isPresent()) {
      errors.add(INVALID_FIELDS);
      return new PossibleError(errors);
    }

    switch (roleFromInvitationType.get()) {
      case INVITED_TO_INTERVIEW:
        Interview interview = invitation.getInterview();
        if (interview == null) {
          errors.add(INVALID_FIELDS);
          return new PossibleError(errors);
        }

        if (!permission.hasRole(INVITED_TO_INTERVIEW)) {
          errors.add(INSUFFICIENT_PRIVELAGES);
          return new PossibleError(errors);
        }

        relationship.addRelationship(TO_INTERVIEW);
        relationship.addRelationship(INVITED_TO_INTERVIEW);

        interview.setUser(user);
        interview.setAvailability(NOT_AVAILABLE);
        interviewRepository.save(interview);
        break;
      case INVITED_TO_JOIN:
        if (permission.canJoin() != JoinResult.HAS_INVITE) {
          errors.add(INSUFFICIENT_PRIVELAGES);
          return new PossibleError(errors);
        }
        relationship.addRelationship(DEFAULT_USER);
        relationship.removeRelationship(INVITED_TO_JOIN);
        break;
    }

    return new PossibleError(Status.OK);
  }


  private boolean logoutIfLoggedIn(User user, HttpServletRequest request) {
    UserPermission userPermission = permissionFactory.createUserPermission(user);
    if (userPermission.isLoggedIn(request)) {
      Optional<FuseSession> session = fuseSessionController.getSession(request);
      session.ifPresent(s -> fuseSessionController.deleteSession(s));
      return true;
    } else {
      return false;
    }
  }
}
