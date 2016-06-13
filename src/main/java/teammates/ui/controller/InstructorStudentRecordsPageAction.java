package teammates.ui.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.HashMap;

import teammates.common.datatransfer.CommentAttributes;
import teammates.common.datatransfer.CommentParticipantType;
import teammates.common.datatransfer.FeedbackSessionAttributes;
import teammates.common.datatransfer.InstructorAttributes;
import teammates.common.datatransfer.StudentAttributes;
import teammates.common.datatransfer.StudentProfileAttributes;
import teammates.common.exception.EntityDoesNotExistException;
import teammates.common.util.Assumption;
import teammates.common.util.Const;
import teammates.common.util.Const.StatusMessageColor;
import teammates.common.util.StatusMessage;
import teammates.logic.api.GateKeeper;

public class InstructorStudentRecordsPageAction extends Action {
    
    private String courseId;
    private InstructorAttributes instructor;

    @Override
    public ActionResult execute() throws EntityDoesNotExistException {

        courseId = getRequestParamValue(Const.ParamsNames.COURSE_ID);
        Assumption.assertNotNull(courseId);

        instructor = logic.getInstructorForGoogleId(courseId, account.googleId);
        new GateKeeper().verifyAccessible(instructor, logic.getCourse(courseId));
        
        String studentEmail = getRequestParamValue(Const.ParamsNames.STUDENT_EMAIL);
        Assumption.assertNotNull(studentEmail);

        StudentAttributes student = logic.getStudentForEmail(courseId, studentEmail);

        if (student == null) {
            statusToUser.add(new StatusMessage(Const.StatusMessages.STUDENT_NOT_FOUND_FOR_RECORDS, StatusMessageColor.DANGER));
            isError = true;
            return createRedirectResult(Const.ActionURIs.INSTRUCTOR_HOME_PAGE);
        }
        
        String showCommentBox = getRequestParamValue(Const.ParamsNames.SHOW_COMMENT_BOX);

        List<CommentAttributes> comments = logic.getCommentsForReceiver(courseId, CommentParticipantType.PERSON,
                                                                        studentEmail);
        
        HashMap<String, List<CommentAttributes>> giverEmailToCommentsMap = mapCommentsToGiverEmail(comments);

        List<FeedbackSessionAttributes> sessions = logic.getFeedbackSessionsListForInstructor(account.googleId);

        filterFeedbackSessions(courseId, sessions, instructor, student);

        Collections.sort(sessions, FeedbackSessionAttributes.DESCENDING_ORDER);
        CommentAttributes.sortCommentsByCreationTimeDescending(comments);
        
        StudentProfileAttributes studentProfile = null;

        boolean isInstructorAllowedToViewStudent = instructor.isAllowedForPrivilege(student.section,
                                                        Const.ParamsNames.INSTRUCTOR_PERMISSION_VIEW_STUDENT_IN_SECTIONS);
        boolean isStudentWithProfile = !student.googleId.isEmpty();
        if (isInstructorAllowedToViewStudent && isStudentWithProfile) {
            studentProfile = logic.getStudentProfile(student.googleId);
            Assumption.assertNotNull(studentProfile);
        } else {
            if (student.googleId.isEmpty()) {
                statusToUser.add(new StatusMessage(Const.StatusMessages.STUDENT_NOT_JOINED_YET_FOR_RECORDS,
                                                   StatusMessageColor.WARNING));
            } else if (!isInstructorAllowedToViewStudent) {
                statusToUser.add(new StatusMessage(Const.StatusMessages.STUDENT_PROFILE_UNACCESSIBLE_TO_INSTRUCTOR,
                                                   StatusMessageColor.WARNING));
            }
        }

        if (sessions.isEmpty() && comments.isEmpty()) {
            statusToUser.add(new StatusMessage(Const.StatusMessages.INSTRUCTOR_NO_STUDENT_RECORDS, StatusMessageColor.WARNING));
        }

        List<String> sessionNames = new ArrayList<String>();
        for (FeedbackSessionAttributes fsa : sessions) {
            sessionNames.add(fsa.getFeedbackSessionName());
        }
        
        InstructorStudentRecordsPageData data =
                                        new InstructorStudentRecordsPageData(account, student, courseId,
                                                                             showCommentBox, studentProfile,
                                                                             giverEmailToCommentsMap,
                                                                             sessionNames, instructor);

        statusToAdmin = "instructorStudentRecords Page Load<br>"
                      + "Viewing <span class=\"bold\">" + studentEmail + "'s</span> records "
                      + "for Course <span class=\"bold\">[" + courseId + "]</span><br>"
                      + "Number of sessions: " + sessions.size() + "<br>"
                      + "Student Profile: " + (studentProfile == null ? "No Profile"
                                                                      : studentProfile.toString());

        return createShowPageResult(Const.ViewURIs.INSTRUCTOR_STUDENT_RECORDS, data);
    }

    private void filterFeedbackSessions(String courseId, List<FeedbackSessionAttributes> feedbacks,
                                        InstructorAttributes instructor, StudentAttributes student) {
        Iterator<FeedbackSessionAttributes> iterFs = feedbacks.iterator();
        while (iterFs.hasNext()) {
            FeedbackSessionAttributes tempFs = iterFs.next();
            if (!tempFs.getCourseId().equals(courseId)
                    || !instructor.isAllowedForPrivilege(student.section, tempFs.getSessionName(),
                                                         Const.ParamsNames.INSTRUCTOR_PERMISSION_VIEW_SESSION_IN_SECTIONS)) {
                iterFs.remove();
            }
        }
    }
    
    private HashMap<String, List<CommentAttributes>> mapCommentsToGiverEmail(List<CommentAttributes> comments) {
        HashMap<String, List<CommentAttributes>> giverEmailToCommentsMap =
                new HashMap<String, List<CommentAttributes>>();
        for (CommentAttributes comment : comments) {
            boolean isCurrentInstructorGiver = comment.giverEmail.equals(instructor.email);
            String key = isCurrentInstructorGiver
                       ? InstructorCommentsPageData.COMMENT_GIVER_NAME_THAT_COMES_FIRST
                       : comment.giverEmail;

            List<CommentAttributes> commentList = giverEmailToCommentsMap.get(key);
            if (commentList == null) {
                commentList = new ArrayList<CommentAttributes>();
                giverEmailToCommentsMap.put(key, commentList);
            }
            updateCommentList(comment, isCurrentInstructorGiver, commentList);
        }
        return giverEmailToCommentsMap;
    }

    private void updateCommentList(CommentAttributes comment,
                                   boolean isCurrentInstructorGiver,
                                   List<CommentAttributes> commentList) {
        if (isCurrentInstructorGiver || 
                isInstructorAllowedForPrivilegeOnComment(comment, instructor, courseId,
                        Const.ParamsNames.INSTRUCTOR_PERMISSION_VIEW_COMMENT_IN_SECTIONS)) {
            commentList.add(comment);
        }
    }
    
    private boolean isInstructorAllowedForPrivilegeOnComment(CommentAttributes comment, InstructorAttributes instructor,
                                                             String courseId, String privilegeName) {
        
        // student records only shows comments targeted at the student, and not team/section
        if (instructor == null || comment.recipientType != CommentParticipantType.PERSON) {
            return false;
        }
        
        String studentEmail = "";
        String section = "";
        if (!comment.recipients.isEmpty()) {
            Iterator<String> iterator = comment.recipients.iterator();
            studentEmail = iterator.next();
        }
        StudentAttributes student = logic.getStudentForEmail(courseId, studentEmail);
        if (student != null) {
            section = student.section;
            return instructor.isAllowedForPrivilege(section, privilegeName);
        } else {
            return false;
        }
    }

}
