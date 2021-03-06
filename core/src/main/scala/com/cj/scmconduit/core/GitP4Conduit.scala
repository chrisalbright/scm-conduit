package com.cj.scmconduit.core;

import java.io.{File, StringReader, PrintStream}
import java.util.ArrayList
import java.util.Arrays
import java.util.List
import java.util.regex.Pattern
import org.apache.commons.io.{IOUtils, FileUtils}
import com.cj.scmconduit.core.git.GitRevisionInfo
import com.cj.scmconduit.core.git.GitStatus
import com.cj.scmconduit.core.p4.P4Changelist
import com.cj.scmconduit.core.p4.P4ClientId
import com.cj.scmconduit.core.p4.P4Credentials
import com.cj.scmconduit.core.p4.P4DepotAddress
import com.cj.scmconduit.core.p4.P4Impl
import com.cj.scmconduit.core.p4.P4
import com.cj.scmconduit.core.p4.ClientSpec
import com.cj.scmconduit.core.p4.P4RevRangeSpec
import com.cj.scmconduit.core.p4.P4RevSpec
import com.cj.scmconduit.core.p4.P4SyncOutputParser.Change
import com.cj.scmconduit.core.p4.createDummyInitialP4Commit
import com.cj.scmconduit.core.util.CommandRunner
import scala.collection.JavaConversions._
import java.io.ByteArrayInputStream
import RichFile._
import com.cj.scmconduit.core.git.Git

object GitP4Conduit {
  
	def create(p4Address:P4DepotAddress, spec:ClientSpec, p4FirstCL:Integer, shell:CommandRunner, credentials:P4Credentials, out:PrintStream, observer:(Conduit)=>Unit = {c=>}) {
		    spec.localPath.mkdirs()
		    
			val p4:P4 = new P4Impl(
					p4Address, 
					new P4ClientId(spec.clientId),
					credentials,
					spec.localPath, 
					shell)
		    
		    val git = new Git(shell, spec.localPath);
		    
			out.println(spec)
			val output = p4.doCommand(new ByteArrayInputStream(spec.toString().getBytes()), "client", "-i")
			out.println(output)
			
			git.run("init")
					
			val lastSyncedP4Changelist = if(p4FirstCL==0) p4FirstCL else p4FirstCL - 1
			
			spec.localPath/".scm-conduit" write(
				<scm-conduit-state>
					<last-synced-p4-changelist>{lastSyncedP4Changelist}</last-synced-p4-changelist>
					<p4-port>{p4Address}</p4-port>
					<p4-read-user>{spec.owner}</p4-read-user>
					<p4-client-id>{spec.clientId}</p4-client-id>
				</scm-conduit-state>
			    )
			    
			val conduit = new GitP4Conduit(spec.localPath, shell, out)
			observer(conduit);
			createDummyInitialP4Commit(spec.localPath, p4, conduit)
			    
	}
	
}

class GitP4Conduit(private val conduitPath:File, private val shell:CommandRunner, private val out:PrintStream) extends Conduit {
//	private static final String TEMP_FILE_NAME=".scm-conduit-temp";
	private val META_FILE_NAME=".scm-conduit";

	private val p4:P4 = {
			  val s = state();
			  new P4Impl(
						new P4DepotAddress(s.p4Port), 
						new P4ClientId(s.p4ClientId),
						new P4Credentials(s.p4ReadUser, null),
						conduitPath, 
						shell
				)
		  }

	override def commit(using:P4Credentials) {}
	
	override def rollback() {
		git.run("reset", "--hard");
	}
	
	
	var backlogSize:Int = 0;
	
	override def currentP4Changelist() = state().getLastSyncedP4Changelist();
	
	override def delete(){
		val s = state();
		p4.doCommand("workspace", "-d", s.getP4ClientId())
		try{
			FileUtils.deleteDirectory(conduitPath);
		}catch{
		  case _ => shell.run("rm", "-rf", conduitPath.getAbsolutePath())
		}
	}
	
	override def push() {
		val p4TimeZoneOffset = findP4TimeZoneOffset();

		var keepPumping = true;
		while(keepPumping){
			val lastSync = findLastSyncRevision();
			val newStuff = findDepotChangesSince(lastSync);
			
			backlogSize = newStuff.size()
			
			if(newStuff.isEmpty()){
				keepPumping = false;
			}else{
				val nextChange = newStuff.get(0);
				assertNoGitChanges();
				val changes = p4.syncTo(P4RevSpec.forChangelist(nextChange.id()));
				
				val status = new GitStatus(git.run("status", "-s"))
				
				val filesICareAbout = status.files.filter{change=>change.file != ".scm-conduit"}
				
				if (filesICareAbout.size > 0){
				  
					val gitCommands = new P42GitTranslator(conduitPath).translate(nextChange, changes, p4TimeZoneOffset);
					gitCommands.foreach{command=>
						git.run(command:_*);
					}
					
					git.run("update-server-info")
				}
				
				assertNoGitChanges();
				
				recordLastSuccessfulSync(nextChange.id());
			}
		}
	}
	
	private def findP4TimeZoneOffset():Int = {
		return -7;
	}


	private def findDepotChangesSince(lastSync:Long):List[P4Changelist] = {
		return p4.changesBetween(P4RevRangeSpec.everythingAfter(lastSync));
	}

	private def recordLastSuccessfulSync(id:Long) {
		val s = state();
		s.lastSyncedP4Changelist = id;
		writeState(s);
	}

	def p4Path():String = {
		val clientSpec = p4.doCommand("client", "-o")
		
		var path = ""
		var inViewSection = false;
		IOUtils.readLines(new StringReader(clientSpec)).asInstanceOf[java.util.List[String]].foreach{line=>
		  if(inViewSection){
		    path += line.trim().split(" ")(0).trim()
		  }else if(line.trim().startsWith("View:")){
		    inViewSection = true
		  }
		}
		
		path
	}
	
	private def state():ConduitState = {
		return ConduitState.read(new File(conduitPath, META_FILE_NAME));
	}

	private def writeState(state:ConduitState){
		ConduitState.write(state, new File(conduitPath, META_FILE_NAME));
	}

	private def findLastSyncRevision():Long =  {
		return state().lastSyncedP4Changelist;
	}

	
	private def git = new Git(shell, this.conduitPath)
	
	private def getGitStatus():GitStatus = {
		return new GitStatus(git.run("status", "-s", "-uno").trim());
	}
	
	private def assertNoGitChanges(){
		val status = git.run("status", "-s", "-uno").trim();
 
		if(!getGitStatus().isUnchanged()){
			throw new RuntimeException("I was expecting there to be no local git changes, but I found some:\n" + status);
		}
	}
	

	private def p4ForUser(using:P4Credentials):P4 = {
		val s = state();
	    new P4Impl(
			new P4DepotAddress(s.p4Port), 
			new P4ClientId(s.p4ClientId),
			using,
			conduitPath, 
			shell);
	}
	
	override def pull(source:String, using:P4Credentials):Boolean = {
		try{
			git.run("remote", "rm", "temp");
		}catch{
		  case _=>// nothing to do
		}
		try{
			git.run("branch", "-D", "incoming");
		}catch{
		  case _=>// nothing to do
		}
		  
		val currentRev = git.run("log", "-1", "--format=%H").trim();
		git.run("remote", "add", "temp", source);
		git.run("fetch", "temp");
		git.run("branch", "incoming", "temp/master");
		git.run("checkout", "incoming");
		val missing = git.run("cherry", "master");
		git.run("checkout", "master");
		out.println("Missing is " + missing);
		if(missing.isEmpty()){
			return false;
		}else{
			val lines = IOUtils.readLines(new StringReader(missing)).asInstanceOf[List[String]];
			
			lines.foreach{line=>
				this.out.println("Need to fetch " + line);
				val rev = line.replaceAll(Pattern.quote("+"), "").trim();
				git.run("merge", rev);
				val log = git.run("log", "--name-status", currentRev + ".." + rev);
				this.out.println(log);
				
				val changes = new GitRevisionInfo(log);
				val myp4 = p4ForUser(using)
				
				try {
					val changeListNum = new Translator(myp4).translate(changes); 
					myp4.doCommand("submit", "-c", changeListNum.toString());		
					git.run("tag", "cl" + changeListNum.toString())
				}catch{
				  case e:Throwable=> {
					  git.run("reset", "--hard", currentRev)
					  throw e
				  }
				}
				
				push()
			}
			
			git.run("update-server-info")
			return true;
		}
	}

}
