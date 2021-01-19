# Releasing squbs

This documents the steps for the squbs release to maven central.

1. Access to oss.sonatype.org for `org.squbs`
   You must have publishing rights to oss.sonatype.org for squbs groupId (`org.squbs`).  To gain access you should 
   follow the steps documented here for [initial setup][1]. You can still perform all the steps here for release except 
   publishing the deployment to maven central.  This will require access to oss.sonatype.org from one of the squbs 
   maintainers (Akara, Anil or Sherif).


2. Check the external dependencies
   Verify that there are no snapshot dependencies present in the `build.sbt`, `project/versions.scala`
   and `project/plugins.sbt` from the branch that has to be released (`master`).
   Build squbs locally and verify that no external dependencies are snapshots.
   

3. Akka, SBT and Scala Version
   Confirm that the Akka, Akka HTTP, SBT and Scala versions are as expected in squbs.  We typically attempt to
   release against the latest compatible versions of these dependencies.


4. Update the squbs version in `versions.sbt` 
   Change the project version in `versions.sbt` to the release version (0.X.Y) locally
   and commit.  Update the project version to n+1 SNAPSHOT locally and commit.
   Git push all these change to Github.


5. Tag the git release commit from Github (`RELEASE-0.X.Y`).
   From Github tag the release commit with the tag `RELEASE-0.X.Y` 


5. Travis builds
   Wait for the Travis builds to complete.  There should be two builds. One for the release
   tag and one for the latest changes on the master branch.


6. Publish deployments
   Check the published artifacts are in the sonatype staging area. If all the artifacts look
   good close and release the stage repository.  See section on [releasing deployment][2].


7. Create the release notes on Github.

[1]: https://central.sonatype.org/pages/ossrh-guide.html#initial-setup
[2]: https://central.sonatype.org/pages/releasing-the-deployment.html#releasing-deployment-from-ossrh-to-the-central-repository-introduction